/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.rrapi.request.RequestEnvelope;
import io.github.dsheirer.rrapi.response.Fault;
import io.github.dsheirer.rrapi.response.ResponseBody;
import io.github.dsheirer.rrapi.response.ResponseEnvelope;
import io.github.dsheirer.rrapi.response.SearchFrequencyResponse;
import io.github.dsheirer.rrapi.type.AuthorizationInformation;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Minimal HTTPS transport for the RadioReference request and response models.
 *
 * <p>radio-reference-api 18.0.0 cannot be configured away from its compiled HTTP endpoint.  This client reuses its
 * XML models only.  Production requests use the documented HTTPS service endpoint, the JDK default trust store and
 * hostname verification, no redirects, and bounded connect, request and response-body limits.</p>
 */
final class SecureRadioReferenceSoapClient implements AutoCloseable
{
    private static final URI PRODUCTION_ENDPOINT = URI.create("https://api.radioreference.com/soap2/");
    private static final Duration PRODUCTION_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PRODUCTION_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int PRODUCTION_MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";
    private static final String USER_AGENT = "io.github.dsheirer.rrapi";
    private static final String SOAP_ENV_PREFIX = "SOAP-ENV";
    private static final String SOAP_ENV_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String XML_SCHEMA_PREFIX = "xsd";
    private static final String XML_SCHEMA_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private static final String XML_SCHEMA_INSTANCE_PREFIX = "xsi";
    private static final String XML_SCHEMA_INSTANCE_NAMESPACE = "http://www.w3.org/2001/XMLSchema-instance";
    private static final String RADIO_REFERENCE_PREFIX = "ns1";
    private static final String RADIO_REFERENCE_NAMESPACE = "http://api.radioreference.com/soap2";
    private static final String SEARCH_STATE_FREQUENCY_ACTION =
        RADIO_REFERENCE_NAMESPACE + "#searchStateFreq";

    private final Object mCredentialLock = new Object();
    private final URI mEndpoint;
    private final HttpClient mHttpClient;
    private final Duration mRequestTimeout;
    private final int mMaximumResponseBytes;
    private final String mUserName;
    private final XmlMapper mXmlMapper = XmlMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private char[] mPassword;
    private boolean mClosed;

    static SecureRadioReferenceSoapClient production(String userName, char[] password)
        throws RadioReferenceGatewayException
    {
        return new SecureRadioReferenceSoapClient(PRODUCTION_ENDPOINT, userName, password,
            PRODUCTION_CONNECT_TIMEOUT, PRODUCTION_REQUEST_TIMEOUT, PRODUCTION_MAXIMUM_RESPONSE_BYTES, null);
    }

    SecureRadioReferenceSoapClient(URI endpoint, String userName, char[] password, Duration connectTimeout,
                                   Duration requestTimeout, int maximumResponseBytes, SSLContext sslContext)
        throws RadioReferenceGatewayException
    {
        mEndpoint = secureEndpoint(endpoint);
        mUserName = Objects.requireNonNull(userName);
        mPassword = Arrays.copyOf(Objects.requireNonNull(password), password.length);
        mRequestTimeout = positive(requestTimeout);

        if(maximumResponseBytes <= 0)
        {
            throw new IllegalArgumentException("maximumResponseBytes must be positive");
        }

        mMaximumResponseBytes = maximumResponseBytes;
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(positive(connectTimeout))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_2);

        if(sslContext != null)
        {
            builder.sslContext(sslContext);
        }

        mHttpClient = builder.build();
    }

    static URI productionEndpoint()
    {
        return PRODUCTION_ENDPOINT;
    }

    <T extends ResponseBody> T execute(Function<AuthorizationInformation,RequestEnvelope> requestFactory,
                                       Class<T> responseType) throws RadioReferenceGatewayException
    {
        return execute(requestFactory, responseType, mRequestTimeout);
    }

    /**
     * Executes a request with an endpoint-specific total deadline.  The deadline covers both response headers and
     * the complete bounded response body.
     */
    <T extends ResponseBody> T execute(Function<AuthorizationInformation,RequestEnvelope> requestFactory,
                                       Class<T> responseType, Duration requestTimeout)
        throws RadioReferenceGatewayException
    {
        Objects.requireNonNull(requestFactory);
        return executeEncoded(authorization -> new EncodedRequest(
            requestFactory.apply(authorization).toXmlString(), null), responseType, requestTimeout);
    }

    SearchFrequencyResponse searchStateFrequencies(int stateId, double frequencyMHz)
        throws RadioReferenceGatewayException
    {
        return executeEncoded(authorization -> stateFrequencyRequest(authorization, stateId, frequencyMHz),
            SearchFrequencyResponse.class, mRequestTimeout);
    }

    private <T extends ResponseBody> T executeEncoded(RequestEncoder requestEncoder, Class<T> responseType,
                                                       Duration requestTimeout)
        throws RadioReferenceGatewayException
    {
        Objects.requireNonNull(requestEncoder);
        Objects.requireNonNull(responseType);
        Duration timeout = positive(requestTimeout);
        AuthorizationInformation authorization = authorization();
        AtomicBoolean responseTooLarge = new AtomicBoolean();

        try
        {
            EncodedRequest encodedRequest = requestEncoder.encode(authorization);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(mEndpoint)
                .timeout(timeout)
                .header("Content-Type", CONTENT_TYPE)
                .header("Accept", "text/xml")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(encodedRequest.xml(), StandardCharsets.UTF_8));

            if(encodedRequest.soapAction() != null)
            {
                requestBuilder.header("SOAPAction", encodedRequest.soapAction());
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<byte[]> response = send(request, responseTooLarge, timeout);

            if(responseTooLarge.get())
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            ResponseEnvelope responseEnvelope;

            try
            {
                responseEnvelope = deserialize(response.body());
            }
            catch(IOException exception)
            {
                throw new RadioReferenceGatewayException(response.statusCode() == 200 ?
                    RadioReferenceGatewayException.Kind.INVALID_RESPONSE :
                    RadioReferenceGatewayException.Kind.HTTP_ERROR);
            }

            ResponseBody responseBody = responseEnvelope != null ? responseEnvelope.getResponseBody() : null;

            if(responseBody instanceof Fault fault)
            {
                throw new RadioReferenceGatewayException("AUTH".equalsIgnoreCase(fault.getFaultCode()) ?
                    RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS :
                    RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            if(response.statusCode() != 200)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.HTTP_ERROR);
            }

            if(!responseType.isInstance(responseBody))
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_RESPONSE);
            }

            return responseType.cast(responseBody);
        }
        catch(RadioReferenceGatewayException exception)
        {
            throw exception;
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INTERRUPTED);
        }
        catch(IOException exception)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.REQUEST_ENCODING);
        }
        catch(RuntimeException exception)
        {
            if(responseTooLarge.get())
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INVALID_RESPONSE);
        }
        finally
        {
            authorization.setPassword("");
        }
    }

    /**
     * The dependency's document-style serializer emits an unbound operation prefix.  RadioReference accepts that
     * legacy request but returns an empty frequency array.  Frequency search therefore uses the service's documented
     * RPC encoding explicitly, including bound namespaces and parameter types.
     */
    private static EncodedRequest stateFrequencyRequest(AuthorizationInformation authorization, int stateId,
                                                         double frequencyMHz) throws IOException
    {
        if(stateId <= 0 || !Double.isFinite(frequencyMHz) || frequencyMHz <= 0)
        {
            throw new IOException("Invalid state-frequency search parameters");
        }

        StringWriter output = new StringWriter();
        XMLStreamWriter xml = null;

        try
        {
            xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            xml.writeStartElement(SOAP_ENV_PREFIX, "Envelope", SOAP_ENV_NAMESPACE);
            xml.writeNamespace(SOAP_ENV_PREFIX, SOAP_ENV_NAMESPACE);
            xml.writeNamespace(RADIO_REFERENCE_PREFIX, RADIO_REFERENCE_NAMESPACE);
            xml.writeNamespace(XML_SCHEMA_PREFIX, XML_SCHEMA_NAMESPACE);
            xml.writeNamespace(XML_SCHEMA_INSTANCE_PREFIX, XML_SCHEMA_INSTANCE_NAMESPACE);
            xml.writeStartElement(SOAP_ENV_PREFIX, "Body", SOAP_ENV_NAMESPACE);
            xml.writeStartElement(RADIO_REFERENCE_PREFIX, "searchStateFreq", RADIO_REFERENCE_NAMESPACE);
            writeTypedElement(xml, "stid", XML_SCHEMA_PREFIX + ":int", Integer.toString(stateId));
            writeTypedElement(xml, "freq", XML_SCHEMA_PREFIX + ":decimal",
                BigDecimal.valueOf(frequencyMHz).stripTrailingZeros().toPlainString());
            writeTypedElement(xml, "tone", XML_SCHEMA_PREFIX + ":string", "");
            xml.writeStartElement("authInfo");
            xml.writeAttribute(XML_SCHEMA_INSTANCE_PREFIX, XML_SCHEMA_INSTANCE_NAMESPACE, "type",
                RADIO_REFERENCE_PREFIX + ":authInfo");
            writeTypedElement(xml, "username", XML_SCHEMA_PREFIX + ":string", authorization.getUserName());
            writeTypedElement(xml, "password", XML_SCHEMA_PREFIX + ":string", authorization.getPassword());
            writeTypedElement(xml, "appKey", XML_SCHEMA_PREFIX + ":string", authorization.getApplicationKey());
            writeTypedElement(xml, "version", XML_SCHEMA_PREFIX + ":string", authorization.getVersion());
            writeTypedElement(xml, "style", XML_SCHEMA_PREFIX + ":string", "rpc");
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            return new EncodedRequest(output.toString(), SEARCH_STATE_FREQUENCY_ACTION);
        }
        catch(XMLStreamException exception)
        {
            throw new IOException("Unable to encode RadioReference frequency search", exception);
        }
        finally
        {
            if(xml != null)
            {
                try
                {
                    xml.close();
                }
                catch(XMLStreamException ignored)
                {
                    //The request content was already produced or the original encoding failure is being reported.
                }
            }
        }
    }

    private static void writeTypedElement(XMLStreamWriter xml, String name, String type, String value)
        throws XMLStreamException
    {
        xml.writeStartElement(name);
        xml.writeAttribute(XML_SCHEMA_INSTANCE_PREFIX, XML_SCHEMA_INSTANCE_NAMESPACE, "type", type);
        xml.writeCharacters(Objects.requireNonNullElse(value, ""));
        xml.writeEndElement();
    }

    private AuthorizationInformation authorization() throws RadioReferenceGatewayException
    {
        synchronized(mCredentialLock)
        {
            if(mClosed)
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            return new AuthorizationInformation(RrapiRadioReferenceGateway.APPLICATION_KEY, mUserName,
                new String(mPassword));
        }
    }

    private HttpResponse.BodyHandler<byte[]> boundedBodyHandler(AtomicBoolean responseTooLarge)
    {
        return responseInfo -> {
            if(responseInfo.headers().firstValueAsLong("Content-Length").orElse(0) >
                mMaximumResponseBytes)
            {
                responseTooLarge.set(true);
            }

            HttpResponse.BodySubscriber<byte[]> limited = HttpResponse.BodySubscribers.limiting(
                HttpResponse.BodySubscribers.ofByteArray(), mMaximumResponseBytes);
            return new ResponseSizeTrackingSubscriber(limited, mMaximumResponseBytes, responseTooLarge);
        };
    }

    /**
     * HttpRequest's native timeout ends when response headers arrive for some body handlers.  Waiting on the
     * asynchronous response future places one deadline around headers and the complete bounded body.
     */
    private HttpResponse<byte[]> send(HttpRequest request, AtomicBoolean responseTooLarge, Duration requestTimeout)
        throws InterruptedException, RadioReferenceGatewayException
    {
        CompletableFuture<HttpResponse<byte[]>> future =
            mHttpClient.sendAsync(request, boundedBodyHandler(responseTooLarge));

        try
        {
            return future.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch(TimeoutException exception)
        {
            future.cancel(true);
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.TIMEOUT);
        }
        catch(ExecutionException exception)
        {
            if(responseTooLarge.get())
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            if(hasCause(exception, HttpTimeoutException.class))
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.TIMEOUT);
            }

            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
        catch(InterruptedException exception)
        {
            future.cancel(true);
            throw exception;
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType)
    {
        Throwable current = throwable;

        while(current != null)
        {
            if(causeType.isInstance(current))
            {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private ResponseEnvelope deserialize(byte[] xml) throws IOException
    {
        if(xml.length == 0)
        {
            return null;
        }

        return mXmlMapper.readValue(xml, ResponseEnvelope.class);
    }

    private static URI secureEndpoint(URI endpoint) throws RadioReferenceGatewayException
    {
        if(endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme()) ||
            endpoint.getHost() == null || endpoint.getHost().isBlank() ||
            endpoint.getUserInfo() != null || endpoint.getFragment() != null)
        {
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.INSECURE_TRANSPORT);
        }

        return endpoint;
    }

    private static Duration positive(Duration duration)
    {
        Objects.requireNonNull(duration);

        if(duration.isZero() || duration.isNegative())
        {
            throw new IllegalArgumentException("timeout must be positive");
        }

        return duration;
    }

    /**
     * Marks an over-limit chunk before the JDK limiting subscriber cancels it.  This lets callers distinguish the
     * deliberate response safety limit from ordinary network failures without depending on an exception message or
     * an internal JDK exception type.
     */
    private static final class ResponseSizeTrackingSubscriber implements HttpResponse.BodySubscriber<byte[]>
    {
        private final HttpResponse.BodySubscriber<byte[]> mDelegate;
        private final long mMaximumBytes;
        private final AtomicBoolean mResponseTooLarge;
        private long mReceivedBytes;

        private ResponseSizeTrackingSubscriber(HttpResponse.BodySubscriber<byte[]> delegate, long maximumBytes,
                                               AtomicBoolean responseTooLarge)
        {
            mDelegate = delegate;
            mMaximumBytes = maximumBytes;
            mResponseTooLarge = responseTooLarge;
        }

        @Override
        public CompletionStage<byte[]> getBody()
        {
            return mDelegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            mDelegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers)
        {
            for(ByteBuffer buffer: buffers)
            {
                int bytes = buffer.remaining();

                if(bytes > mMaximumBytes - mReceivedBytes)
                {
                    mResponseTooLarge.set(true);
                }
                else
                {
                    mReceivedBytes += bytes;
                }
            }

            mDelegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable)
        {
            mDelegate.onError(throwable);
        }

        @Override
        public void onComplete()
        {
            mDelegate.onComplete();
        }
    }

    @Override
    public void close()
    {
        synchronized(mCredentialLock)
        {
            if(mClosed)
            {
                return;
            }

            mClosed = true;
            Arrays.fill(mPassword, '\0');
            mPassword = new char[0];
        }

        mHttpClient.shutdownNow();
    }

    @FunctionalInterface
    private interface RequestEncoder
    {
        EncodedRequest encode(AuthorizationInformation authorization) throws IOException;
    }

    private record EncodedRequest(String xml, String soapAction)
    {
        private EncodedRequest
        {
            Objects.requireNonNull(xml);
        }
    }
}

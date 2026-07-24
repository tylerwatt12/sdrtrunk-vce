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

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.rrapi.request.RequestEnvelope;
import io.github.dsheirer.rrapi.response.Fault;
import io.github.dsheirer.rrapi.response.ResponseBody;
import io.github.dsheirer.rrapi.response.ResponseEnvelope;
import io.github.dsheirer.rrapi.type.AuthorizationInformation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private final Object mCredentialLock = new Object();
    private final URI mEndpoint;
    private final HttpClient mHttpClient;
    private final Duration mRequestTimeout;
    private final int mMaximumResponseBytes;
    private final String mUserName;
    private final XmlMapper mXmlMapper = new XmlMapper();
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
        Objects.requireNonNull(requestFactory);
        Objects.requireNonNull(responseType);
        AuthorizationInformation authorization = authorization();
        AtomicBoolean responseTooLarge = new AtomicBoolean();

        try
        {
            RequestEnvelope envelope = requestFactory.apply(authorization);
            String requestXml = envelope.toXmlString();
            HttpRequest request = HttpRequest.newBuilder(mEndpoint)
                .timeout(mRequestTimeout)
                .header("Content-Type", CONTENT_TYPE)
                .header("Accept", "text/xml")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(requestXml, StandardCharsets.UTF_8))
                .build();
            HttpResponse<byte[]> response = send(request, responseTooLarge);

            if(responseTooLarge.get())
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            ResponseEnvelope responseEnvelope = deserialize(response.body());
            ResponseBody responseBody = responseEnvelope != null ? responseEnvelope.getResponseBody() : null;

            if(responseBody instanceof Fault fault)
            {
                throw new RadioReferenceGatewayException("AUTH".equalsIgnoreCase(fault.getFaultCode()) ?
                    RadioReferenceGatewayException.Kind.INVALID_CREDENTIALS :
                    RadioReferenceGatewayException.Kind.UNAVAILABLE);
            }

            if(response.statusCode() != 200 || !responseType.isInstance(responseBody))
            {
                throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
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
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
        catch(IOException | RuntimeException exception)
        {
            if(responseTooLarge.get())
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
        finally
        {
            authorization.setPassword("");
        }
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
    private HttpResponse<byte[]> send(HttpRequest request, AtomicBoolean responseTooLarge)
        throws InterruptedException, RadioReferenceGatewayException
    {
        CompletableFuture<HttpResponse<byte[]>> future =
            mHttpClient.sendAsync(request, boundedBodyHandler(responseTooLarge));

        try
        {
            return future.get(mRequestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        }
        catch(TimeoutException exception)
        {
            future.cancel(true);
            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
        catch(ExecutionException exception)
        {
            if(responseTooLarge.get())
            {
                throw new RadioReferenceGatewayException(
                    RadioReferenceGatewayException.Kind.RESULT_SET_TOO_LARGE);
            }

            throw new RadioReferenceGatewayException(RadioReferenceGatewayException.Kind.UNAVAILABLE);
        }
        catch(InterruptedException exception)
        {
            future.cancel(true);
            throw exception;
        }
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
}

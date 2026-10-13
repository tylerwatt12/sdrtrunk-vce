package io.github.dsheirer.alerts.delivery;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** RFC 8291 single-record encryption and RFC 8292 VAPID using Java's standard P-256 provider. */
public final class WebPushCodec
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private WebPushCodec() { }

    public record Keys(String publicKey, String privateKey)
    {
        @Override public String toString() { return "WebPushKeys[redacted]"; }
    }

    public static Keys generateKeys() throws GeneralSecurityException
    {
        KeyPair pair = keyPair();
        return new Keys(ENCODER.encodeToString(publicBytes((ECPublicKey)pair.getPublic())),
            ENCODER.encodeToString(pair.getPrivate().getEncoded()));
    }

    public static byte[] encrypt(byte[] payload, String p256dh, String auth) throws GeneralSecurityException
    {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(payload, DECODER.decode(p256dh), DECODER.decode(auth), keyPair(), salt);
    }

    static byte[] encrypt(byte[] payload, byte[] receiver, byte[] auth, KeyPair sender, byte[] salt)
        throws GeneralSecurityException
    {
        if(payload.length > 3993 || auth.length != 16 || salt.length != 16)
        {
            throw new IllegalArgumentException("Invalid Web Push message or subscription");
        }
        ECPublicKey receiverKey = publicKey(receiver);
        byte[] senderPublic = publicBytes((ECPublicKey)sender.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(sender.getPrivate());
        agreement.doPhase(receiverKey, true);
        byte[] shared = agreement.generateSecret();
        byte[] info = ByteBuffer.allocate(15 + 65 + 65).put("WebPush: info\0".getBytes(StandardCharsets.US_ASCII))
            .put(receiver).put(senderPublic).put((byte)1).array();
        // The final octet is HKDF's expansion counter; the NUL-terminated label occupies 14 octets.
        byte[] ikm = hmac(hmac(auth, shared), info);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = Arrays.copyOf(payload, payload.length + 1);
        plain[payload.length] = 2;
        byte[] encrypted = cipher.doFinal(plain);
        // RFC 8188 salt, record size, key length and uncompressed ephemeral key.
        return ByteBuffer.allocate(86 + encrypted.length).put(salt).putInt(4096).put((byte)65)
            .put(senderPublic).put(encrypted).array();
    }

    public static String authorization(Keys keys, String audience, String subject, long expiresAtSeconds)
        throws GeneralSecurityException
    {
        if(!audience.matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?") ||
            !subject.matches("https://[A-Za-z0-9.-]+(?::[0-9]+)?(?:/[^\"\\\\\\s]*)?"))
        {
            throw new IllegalArgumentException("Invalid Web Push identity");
        }
        String header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        String claims = encode("{\"aud\":\"" + audience + "\",\"exp\":" + expiresAtSeconds +
            ",\"sub\":\"" + subject + "\"}");
        String message = header + "." + claims;
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(
            new PKCS8EncodedKeySpec(DECODER.decode(keys.privateKey())));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update(message.getBytes(StandardCharsets.US_ASCII));
        return "vapid t=" + message + "." + ENCODER.encodeToString(signer.sign()) + ", k=" + keys.publicKey();
    }

    public static void validateSubscription(String p256dh, String auth) throws GeneralSecurityException
    {
        publicKey(DECODER.decode(p256dh));
        if(DECODER.decode(auth).length != 16) throw new IllegalArgumentException("Invalid subscription key");
    }

    static ECPublicKey publicKey(byte[] raw) throws GeneralSecurityException
    {
        if(raw.length != 65 || raw[0] != 4) throw new IllegalArgumentException("Invalid P-256 public key");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        BigInteger p = ((java.security.spec.ECFieldFp)spec.getCurve().getField()).getP();
        if(x.compareTo(p) >= 0 || y.compareTo(p) >= 0 ||
            !y.multiply(y).mod(p).equals(x.multiply(x).multiply(x).add(spec.getCurve().getA().multiply(x))
                .add(spec.getCurve().getB()).mod(p)))
        {
            throw new IllegalArgumentException("Invalid P-256 public key");
        }
        return (ECPublicKey)KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    static byte[] publicBytes(ECPublicKey key)
    {
        byte[] raw = new byte[65]; raw[0] = 4;
        putCoordinate(raw, 1, key.getW().getAffineX());
        putCoordinate(raw, 33, key.getW().getAffineY());
        return raw;
    }

    private static void putCoordinate(byte[] target, int offset, BigInteger value)
    {
        byte[] bytes = value.toByteArray();
        int length = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - length, target, offset + 32 - length, length);
    }

    private static KeyPair keyPair() throws GeneralSecurityException
    {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] hmac(byte[] key, byte[] input) throws GeneralSecurityException
    {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input);
    }

    private static String encode(String value)
    {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

package io.github.dsheirer.alerts.delivery;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebPushCodecTest
{
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    @Test void matchesRfc8291Section5PublishedVector() throws Exception
    {
        byte[] ua = B64.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] sender = B64.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        var privateKey = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(
            new BigInteger(1, B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")),
            params.getParameterSpec(ECParameterSpec.class)));
        byte[] encrypted = WebPushCodec.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.US_ASCII),
            ua, B64.decode("BTBZMqHH6r4Tts7J_aSIgg"), new KeyPair(WebPushCodec.publicKey(sender), privateKey),
            B64.decode("DGv6ra1nlYgDCS1FRnbzlw"));
        assertArrayEquals(B64.decode("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
            "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
            "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"), encrypted);
    }

    @Test void vapidIsValidP1363Es256AndUsesSeparateSigningKey() throws Exception
    {
        var keys = WebPushCodec.generateKeys();
        String header = WebPushCodec.authorization(keys, "https://fcm.googleapis.com", "https://vce.example", 1700000000);
        String jwt = header.substring(8, header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        assertEquals(64, B64.decode(parts[2]).length);
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initVerify(WebPushCodec.publicKey(B64.decode(keys.publicKey())));
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(B64.decode(parts[2])));
        assertFalse(keys.toString().contains(keys.privateKey()));
    }

    @Test void rejectsInvalidCurvePointsAndPrivateOrRedirectDestinations()
    {
        assertThrows(IllegalArgumentException.class, () -> WebPushCodec.publicKey(new byte[65]));
        for(String url: new String[]{"http://fcm.googleapis.com/send", "https://127.0.0.1/send",
            "https://fcm.googleapis.com.evil.example/send", "https://fcm.googleapis.com:8443/send",
            "https://user@fcm.googleapis.com/send", "https://web.push.apple.com/send#secret"})
            assertThrows(IllegalArgumentException.class, () -> DirectWebPushSender.validateEndpoint(url));
        assertEquals("fcm.googleapis.com", DirectWebPushSender.validateEndpoint("https://fcm.googleapis.com/send/token").getHost());
    }
}

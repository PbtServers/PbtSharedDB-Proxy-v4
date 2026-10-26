package com.pbtservers.sharedb;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

public final class PinnedTls {
    private PinnedTls() {}
    public static SSLContext client(String fingerprint) throws GeneralSecurityException {
        if(fingerprint==null || !fingerprint.matches("[a-fA-F0-9]{64}"))throw new GeneralSecurityException("Falta la huella SHA-256 del certificado");
        byte[] expected=HexFormat.of().parseHex(fingerprint);
        X509TrustManager trust=new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c,String auth) throws CertificateException { throw new CertificateException("Uso de cliente no permitido"); }
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                if(chain==null || chain.length==0)throw new CertificateException("Sin certificado");
                chain[0].checkValidity();
                try { if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())))throw new CertificateException("Certificado no autorizado"); }
                catch(NoSuchAlgorithmException e) { throw new CertificateException(e); }
            }
        };
        SSLContext context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},new SecureRandom());return context;
        // HttpClient conserva además la comprobación HTTPS de nombre/SAN. No se desactiva.
    }
}

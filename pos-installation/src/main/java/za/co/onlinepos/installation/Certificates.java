package za.co.onlinepos.installation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class Certificates {
  private Certificates() {}

  public static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    return generator.generateKeyPair();
  }

  public static X509Certificate parse(String encoded) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
  }

  public static String encode(X509Certificate certificate) throws Exception {
    return Base64.getEncoder().encodeToString(certificate.getEncoded());
  }

  public static String fingerprint(X509Certificate certificate) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
  }

  public static X509Certificate issue(
      String subject,
      java.security.PublicKey publicKey,
      X509Certificate issuer,
      PrivateKey signingKey,
      boolean ca,
      boolean client,
      List<String> hosts)
      throws Exception {
    Instant now = Instant.now();
    X500Name name = new X500Name("CN=" + subject);
    X500Name issuerName =
        issuer == null ? name : new X500Name(issuer.getSubjectX500Principal().getName());
    var builder =
        new JcaX509v3CertificateBuilder(
            issuerName,
            new BigInteger(160, new SecureRandom()),
            Date.from(now.minusSeconds(60)),
            Date.from(now.plusSeconds(ca ? 315360000L : 31536000L)),
            name,
            publicKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    builder.addExtension(
        Extension.keyUsage,
        true,
        new KeyUsage(
            ca
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign
                : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
    if (!ca)
      builder.addExtension(
          Extension.extendedKeyUsage,
          false,
          new ExtendedKeyUsage(
              client ? KeyPurposeId.id_kp_clientAuth : KeyPurposeId.id_kp_serverAuth));
    if (!hosts.isEmpty())
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(
              hosts.stream()
                  .map(
                      host ->
                          new GeneralName(
                              host.matches("[0-9.]+") ? GeneralName.iPAddress : GeneralName.dNSName,
                              host))
                  .toArray(GeneralName[]::new)));
    return new JcaX509CertificateConverter()
        .getCertificate(
            builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(signingKey)));
  }

  public static byte[] store(
      PrivateKey identityKey, X509Certificate leaf, X509Certificate ca, char[] password)
      throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, password);
    if (identityKey != null)
      store.setKeyEntry(
          "identity", identityKey, password, new java.security.cert.Certificate[] {leaf, ca});
    store.setCertificateEntry("cluster-ca", ca);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    store.store(output, password);
    return output.toByteArray();
  }

  public static PrivateKey privateKey(byte[] encoded) throws Exception {
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
  }
}

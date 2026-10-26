package za.co.onlinepos.server;

import java.net.InetAddress;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import za.co.onlinepos.installation.NetworkSettings;
import za.co.onlinepos.installation.WindowsSecretStore;

/** A distinct loopback listener, rather than a wildcard listener on every interface. */
@Configuration(proxyBeanMethods = false)
@Profile("installed")
public class LoopbackTls {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> localConnector() throws Exception {
    var root = InstalledConfiguration.dataRoot();
    String password =
        new String(
            new WindowsSecretStore(root).get("pki-password"),
            java.nio.charset.StandardCharsets.US_ASCII);
    int port = NetworkSettings.load(root).httpsPort();
    return factory -> {
      var connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
      connector.setPort(port);
      connector.setSecure(true);
      connector.setScheme("https");
      var protocol = (Http11NioProtocol) connector.getProtocolHandler();
      try {
        protocol.setAddress(InetAddress.getByName("127.0.0.1"));
      } catch (java.net.UnknownHostException e) {
        throw new IllegalStateException(e);
      }
      protocol.setSSLEnabled(true);
      var host = new SSLHostConfig();
      host.setCertificateVerification("optional");
      host.setTruststoreFile(root.resolve("runtime/trust.p12").toString());
      host.setTruststorePassword(password);
      host.setTruststoreType("PKCS12");
      var certificate = new SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.RSA);
      certificate.setCertificateKeystoreFile(root.resolve("runtime/server.p12").toString());
      certificate.setCertificateKeystorePassword(password);
      certificate.setCertificateKeystoreType("PKCS12");
      host.addCertificate(certificate);
      protocol.addSslHostConfig(host);
      factory.addAdditionalTomcatConnectors(connector);
    };
  }
}

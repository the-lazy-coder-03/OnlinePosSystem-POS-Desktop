package za.co.onlinepos.installation;

import java.net.URI;
import java.util.UUID;

public final class Pairing {
  private Pairing() {}

  public record Invitation(
      int version,
      UUID clusterId,
      int branchId,
      String endpoint,
      String caCertificate,
      String caFingerprint,
      String token,
      long expiresAt) {
    public Invitation {
      if (version != 1 || clusterId == null || token == null || !token.matches("[A-Za-z0-9_-]{43}"))
        throw new IllegalArgumentException("Invalid invitation");
      za.co.onlinepos.contracts.Branch.fromId(branchId);
      URI uri = URI.create(endpoint);
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
        throw new IllegalArgumentException("Invitation requires an HTTPS origin");
    }

    @Override
    public String toString() {
      return "PairingInvitation[cluster=" + clusterId + ", token=REDACTED]";
    }
  }

  public record Request(
      UUID clusterId,
      int branchId,
      UUID terminalId,
      String token,
      String publicKey,
      String signature) {
    @Override
    public String toString() {
      return "PairingRequest[terminal=" + terminalId + ", token=REDACTED]";
    }
  }

  public record Bootstrap(
      int version,
      UUID installationId,
      UUID clusterId,
      int branchId,
      String role,
      String state,
      String caCertificate,
      String caFingerprint) {}

  public record JoinRequest(
      UUID clusterId, int branchId, UUID terminalId, String terminalName, String publicKey) {}

  public record Challenge(String nonce, long expiresAt) {}

  public record Enrolment(JoinRequest terminal, String nonce, String signature) {}

  public static String proof(JoinRequest request, String nonce) throws Exception {
    return JsonFiles.JSON.writeValueAsString(
        java.util.List.of(
            "OnlinePOS-LAN-v1",
            request.clusterId(),
            request.branchId(),
            request.terminalId(),
            request.terminalName(),
            request.publicKey(),
            nonce));
  }

  public record Reply(UUID clusterId, int branchId, UUID terminalId, String clientCertificate) {}

  public record Identity(
      UUID installationId, UUID clusterId, int branchId, String role, String state) {}
}

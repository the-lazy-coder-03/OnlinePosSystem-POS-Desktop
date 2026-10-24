package za.co.onlinepos.installation;

public interface StoreApiClient {
  Pairing.Identity identity(Pairing.Invitation invitation, SecretStore secrets) throws Exception;
}

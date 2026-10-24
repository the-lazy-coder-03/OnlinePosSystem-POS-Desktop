package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import za.co.onlinepos.contracts.HealthResponse;
import za.co.onlinepos.server.StoreServerApplication;

@SpringBootTest(
    classes = StoreServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthHttpIT {
  @Autowired private TestRestTemplate http;

  @Test
  void realHttpServerReturnsTheSharedContractWithoutAnyDatabaseOrCloudConnection() {
    var response = http.getForEntity("/api/local/health", HealthResponse.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(HealthResponse.foundation(), response.getBody());
    assertFalse(response.getBody().acceptingSales());
    assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
  }
}

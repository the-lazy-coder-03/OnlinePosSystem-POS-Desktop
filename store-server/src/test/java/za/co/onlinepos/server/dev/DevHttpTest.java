package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import za.co.onlinepos.server.StoreServerApplication;

class DevHttpTest {
  @TempDir Path directory;

  @Test
  void authenticatesEveryRequestAndKeepsWritesOutOfOtherProfiles() throws Exception {
    var client = HttpClient.newHttpClient();
    var json = new ObjectMapper();
    try (var context =
        new SpringApplicationBuilder(StoreServerApplication.class)
            .profiles("dev")
            .run(
                "--server.port=0",
                "--pos.dev.data-dir=" + directory.resolve("dev"),
                "--pos.dev.legacy-dir=" + directory.resolve("absent"))) {
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      String endpoint = "http://127.0.0.1:" + port + "/api/local/dev/data-manager";
      var identity = context.getBean(DevDatabase.Identity.class);
      assertEquals(
          403,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(endpoint + "/categories")).GET().build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      var status =
          client.send(
              HttpRequest.newBuilder(URI.create(endpoint + "/status"))
                  .header("Authorization", "Bearer " + identity.token())
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, status.statusCode());
      assertEquals(identity.instanceId(), json.readTree(status.body()).path("instanceId").asText());
      assertFalse(status.body().contains(identity.token()));
      String payload =
          json.writeValueAsString(
              new za.co.onlinepos.contracts.DevData.Save(
                  UUID.randomUUID(), null, "HTTP category", 3, true));
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + "/categories"))
              .header("Authorization", "Bearer " + identity.token())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      var saved = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, saved.statusCode());
      assertEquals(saved.body(), client.send(request, HttpResponse.BodyHandlers.ofString()).body());
      var listed =
          client.send(
              HttpRequest.newBuilder(
                      URI.create(endpoint + "/categories?state=all&search=HTTP&page=0&size=50"))
                  .header("Authorization", "Bearer " + identity.token())
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, listed.statusCode());
      assertEquals(1, json.readTree(listed.body()).path("total").asInt());
      int id = json.readTree(saved.body()).path("id").asInt();
      String edited =
          json.writeValueAsString(
              new za.co.onlinepos.contracts.DevData.Save(
                  UUID.randomUUID(), 0L, "HTTP edited", 4, false));
      var updated =
          client.send(
              HttpRequest.newBuilder(URI.create(endpoint + "/categories/" + id))
                  .header("Authorization", "Bearer " + identity.token())
                  .header("Content-Type", "application/json")
                  .PUT(HttpRequest.BodyPublishers.ofString(edited))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, updated.statusCode());
      assertFalse(json.readTree(updated.body()).path("active").asBoolean());

      assertEquals(
          403,
          client
              .send(
                  HttpRequest.newBuilder(URI.create(endpoint + "/status"))
                      .header("Authorization", "Bearer wrong")
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
      var filter = new DevAuthenticationFilter(identity);
      var external =
          new org.springframework.mock.web.MockHttpServletRequest(
              "GET", "/api/local/dev/data-manager/status");
      external.setRemoteAddr("192.168.1.5");
      external.addHeader("Authorization", "Bearer " + identity.token());
      var response = new org.springframework.mock.web.MockHttpServletResponse();
      filter.doFilter(external, response, new org.springframework.mock.web.MockFilterChain());
      assertEquals(403, response.getStatus());
    }
    try (var context =
        new SpringApplicationBuilder(StoreServerApplication.class).run("--server.port=0")) {
      assertEquals(0, context.getBeansOfType(DevDataEndpoints.class).size());
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      assertEquals(
          403,
          client
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://127.0.0.1:" + port + "/api/local/dev/data-manager/status"))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode());
    }
  }
}

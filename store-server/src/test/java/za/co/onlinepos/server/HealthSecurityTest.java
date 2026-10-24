package za.co.onlinepos.server;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthSecurityTest {
  @Autowired private MockMvc http;

  @Test
  void livenessDoesNotClaimThatSalesCloudOrAutomaticPromotionAreReady() throws Exception {
    http.perform(get("/api/local/health"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.role").value("UNCONFIGURED"))
        .andExpect(jsonPath("$.acceptingSales").value(false))
        .andExpect(jsonPath("$.cloudSyncEnabled").value(false))
        .andExpect(jsonPath("$.automaticPromotionEnabled").value(false));
  }

  @Test
  void noOrderSubmissionOrProvisioningRouteIsAvailable() throws Exception {
    http.perform(post("/api/local/orders").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    http.perform(get("/api/local/orders")).andExpect(status().isForbidden());
    http.perform(post("/api/local/install")).andExpect(status().isForbidden());
    http.perform(get("/actuator/env")).andExpect(status().isForbidden());
  }
}

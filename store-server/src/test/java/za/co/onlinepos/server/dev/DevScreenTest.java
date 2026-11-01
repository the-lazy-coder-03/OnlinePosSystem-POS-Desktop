package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.ScreenDocuments;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.screen.ScreenService;

class DevScreenTest {
  @TempDir Path directory;

  @Test
  void protectedAtomicRevisionsPublicationRetriesRollbackAndRestart() {
    var fixture = new H2LocalAuthenticationTest();
    fixture.directory = directory;
    UUID screen, owner;
    String hash;
    try (var context = fixture.start()) {
      fixture.setup(context);
      var auth = context.getBean(H2LocalAuthentication.class);
      var db = context.getBean(DevModuleStore.class);
      var service = context.getBean(ScreenService.class);
      owner =
          UUID.fromString(
              auth.bootstrapOwner(UUID.randomUUID(), "owner.sales", "192837".toCharArray())
                  .path("staffId")
                  .asText());
      var session =
          auth.enter(
              "192837".toCharArray(), owner, PosModule.ADMINISTRATION, "test-terminal", false);
      var doc = ScreenDocuments.starter(service.store(), service.catalog());
      screen = doc.screenId();
      var command = new SalesScreens.Save(UUID.randomUUID(), 0, "Screen", false, doc);
      assertThrows(
          LocalFailure.class, () -> service.save("Development actor", "test-terminal", command));
      assertThrows(
          LocalFailure.class, () -> service.save(session.token(), "different-terminal", command));
      var saved = service.save(session.token(), "test-terminal", command);
      hash = saved.path("hash").asText();
      assertEquals(saved, service.save(session.token(), "test-terminal", command));
      assertEquals(
          1,
          db.jdbc.queryForObject("SELECT count(*) FROM dev_meta.screen_revision", Integer.class));
      assertThrows(
          LocalFailure.class,
          () ->
              service.save(
                  session.token(),
                  "test-terminal",
                  new SalesScreens.Save(UUID.randomUUID(), 0, "Stale", false, doc)));
      var publication = new SalesScreens.Publish(UUID.randomUUID(), screen, 1, 0);
      var published = service.publish(session.token(), "test-terminal", publication, false);
      assertEquals(
          published, service.publish(session.token(), "test-terminal", publication, false));
      assertThrows(
          LocalFailure.class,
          () ->
              service.publish(
                  session.token(),
                  "test-terminal",
                  new SalesScreens.Publish(UUID.randomUUID(), screen, 1, 0),
                  false));
      // Mandatory audit failure must roll back pointer, event and receipt together.
      db.jdbc.execute(
          "ALTER TABLE dev_meta.module_events ADD CONSTRAINT reject_screen_audit CHECK(action<>'SALES_SCREEN_PUBLISHED') NOCHECK");
      var failed = new SalesScreens.Publish(UUID.randomUUID(), screen, 1, 1);
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> service.publish(session.token(), "test-terminal", failed, true));
      assertEquals(
          1L,
          db.jdbc.queryForObject(
              "SELECT sequence FROM dev_meta.store_screen_publication", Long.class));
      assertEquals(
          0,
          db.jdbc.queryForObject(
              "SELECT count(*) FROM dev_meta.screen_command WHERE command_id=?",
              Integer.class,
              failed.commandId()));
      db.jdbc.execute("ALTER TABLE dev_meta.module_events DROP CONSTRAINT reject_screen_audit");
      assertEquals(
          2,
          service.publish(session.token(), "test-terminal", failed, true).path("sequence").asInt());
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.jdbc.update("UPDATE dev_meta.screen_revision SET document_hash='tampered'"));
      assertEquals(2, ((java.util.List<?>) service.events(0)).size());
    }
    try (var context = fixture.start()) {
      var service = context.getBean(ScreenService.class);
      assertEquals(hash, service.revision(screen, 1).hash());
      assertNotNull(service.published());
      assertEquals(2, ((java.util.List<?>) service.events(0)).size());
    }
  }

  @Test
  void photoConversionKeepsHistoryAndRollsBackFailedRevision() {
    var fixture = new H2LocalAuthenticationTest();
    fixture.directory = directory;
    try (var context = fixture.start()) {
      fixture.setup(context);
      var auth = context.getBean(H2LocalAuthentication.class);
      var db = context.getBean(DevModuleStore.class);
      var service = context.getBean(ScreenService.class);
      var owner =
          UUID.fromString(
              auth.bootstrapOwner(UUID.randomUUID(), "layout.owner", "192837".toCharArray())
                  .path("staffId")
                  .asText());
      var session =
          auth.enter(
              "192837".toCharArray(), owner, PosModule.ADMINISTRATION, "test-terminal", false);
      var old =
          new SalesScreens.Document(
              1,
              UUID.randomUUID(),
              service.store(),
              new SalesScreens.Canvas(1200, 720, 8),
              java.util.List.of(
                  new SalesScreens.Page(
                      UUID.randomUUID(),
                      "Existing Sales",
                      SalesScreens.Purpose.SALES,
                      java.util.List.of(
                          ScreenDocuments.widget(
                              SalesScreens.Type.ORDER_LIST,
                              "Order",
                              new SalesScreens.Rect(400, 90, 500, 460, 1),
                              java.util.Map.of())))),
              java.util.List.of(),
              java.util.List.of());
      service.save(
          session.token(),
          "test-terminal",
          new SalesScreens.Save(UUID.randomUUID(), 0, "Screen", false, old));
      var converted = za.co.onlinepos.contracts.screen.PhotoSalesLayout.apply(old);
      var save = new SalesScreens.Save(UUID.randomUUID(), 1, "Screen", false, converted);
      assertThrows(
          LocalFailure.class, () -> service.save("Development actor", "test-terminal", save));
      db.jdbc.execute(
          "ALTER TABLE dev_meta.screen_revision ADD CONSTRAINT reject_conversion CHECK(revision<2) NOCHECK");
      try {
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> service.save(session.token(), "test-terminal", save));
        assertEquals(
            1,
            db.jdbc.queryForObject(
                "SELECT revision FROM dev_meta.screen_definition", Integer.class));
        assertEquals(
            0,
            db.jdbc.queryForObject(
                "SELECT count(*) FROM dev_meta.screen_command WHERE command_id=?",
                Integer.class,
                save.commandId()));
      } finally {
        db.jdbc.execute("ALTER TABLE dev_meta.screen_revision DROP CONSTRAINT reject_conversion");
      }
      var result = service.save(session.token(), "test-terminal", save);
      assertEquals(result, service.save(session.token(), "test-terminal", save));
      assertEquals(old, service.revision(old.screenId(), 1).document());
      assertEquals(converted, service.revision(old.screenId(), 2).document());
      assertThrows(
          LocalFailure.class,
          () ->
              service.save(
                  session.token(),
                  "test-terminal",
                  new SalesScreens.Save(
                      UUID.randomUUID(), 1, "Stale conversion", false, converted)));
      service.publish(
          session.token(),
          "test-terminal",
          new SalesScreens.Publish(UUID.randomUUID(), old.screenId(), 2, 0),
          false);
      service.publish(
          session.token(),
          "test-terminal",
          new SalesScreens.Publish(UUID.randomUUID(), old.screenId(), 1, 1),
          true);
      assertEquals(1, ((SalesScreens.Publication) service.published()).revision());
    }
  }

  @Test
  void draftEditsKeepStableLineIdsCheckRevisionAndRollbackBadQuotes() {
    var fixture = new DevGridTest();
    fixture.directory = directory;
    try (var context = fixture.start()) {
      var drafts = context.getBean(DevDraftService.class);
      var catalog = context.getBean(DevCatalogueService.class);
      var p = fixture.simple(catalog);
      UUID order = UUID.randomUUID(), line = UUID.randomUUID();
      var selection =
          za.co.onlinepos.contracts.ItemCustomization.Selection.empty(catalog.catalog().revision());
      var first =
          new za.co.onlinepos.contracts.LocalCommands.DraftLine(
              line, p.priceId(), p.priceRevision(), 1, selection);
      drafts.create(
          new za.co.onlinepos.contracts.LocalCommands.CreateDraft(
              UUID.randomUUID(), order, java.util.List.of(first), null));
      var edit =
          new za.co.onlinepos.contracts.LocalCommands.EditDraft(
              UUID.randomUUID(),
              0,
              java.util.List.of(
                  new za.co.onlinepos.contracts.LocalCommands.DraftLine(
                      line, p.priceId(), p.priceRevision(), 2, selection, "Please cut")),
              null);
      var result = drafts.edit(order, edit);
      assertEquals(result.toString(), drafts.edit(order, edit).toString());
      assertEquals(1, drafts.get(order).path("revision").asInt());
      assertEquals("Please cut", drafts.get(order).path("lineNotes").get(0).asText());
      assertEquals(line.toString(), drafts.get(order).path("lineIds").get(0).asText());
      assertThrows(
          RuntimeException.class,
          () ->
              drafts.edit(
                  order,
                  new za.co.onlinepos.contracts.LocalCommands.EditDraft(
                      UUID.randomUUID(), 0, java.util.List.of(first), null)));
      assertThrows(
          RuntimeException.class,
          () ->
              drafts.edit(
                  order,
                  new za.co.onlinepos.contracts.LocalCommands.EditDraft(
                      UUID.randomUUID(),
                      1,
                      java.util.List.of(
                          new za.co.onlinepos.contracts.LocalCommands.DraftLine(
                              line, p.priceId(), p.priceRevision() + 1, 3, selection)),
                      null)));
      assertEquals(2, drafts.get(order).path("lines").get(0).path("quantity").asInt());
    }
  }

  @Test
  void authoringHttpRejectsDevelopmentActorMalformedDocumentsAndBadAssets() throws Exception {
    var fixture = new H2LocalAuthenticationTest();
    fixture.directory = directory;
    try (var context = fixture.start()) {
      fixture.setup(context);
      var identity = context.getBean(DevDatabase.Identity.class);
      var auth = context.getBean(H2LocalAuthentication.class);
      var screens = context.getBean(ScreenService.class);
      var owner =
          UUID.fromString(
              auth.bootstrapOwner(UUID.randomUUID(), "asset.owner", "123456".toCharArray())
                  .path("staffId")
                  .asText());
      var session =
          auth.enter("123456".toCharArray(), owner, PosModule.ADMINISTRATION, "desktop", false);
      int port =
          ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                  context)
              .getWebServer()
              .getPort();
      var http = java.net.http.HttpClient.newHttpClient();
      String url = "http://127.0.0.1:" + port + "/api/local/dev/data-manager/screens/";
      var denied =
          http.send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "context"))
                  .header("Authorization", "Bearer " + identity.token())
                  .header("X-Screen-Terminal", "desktop")
                  .GET()
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(401, denied.statusCode());
      var image =
          new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
      var bytes = new java.io.ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "png", bytes);
      byte[] png = bytes.toByteArray();
      String hash =
          java.util.HexFormat.of()
              .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
      var asset = new SalesScreens.Asset(UUID.randomUUID(), hash, "image/png", png);
      var uploaded = screens.upload(session.token(), "desktop", asset);
      assertEquals(uploaded, screens.upload(session.token(), "desktop", asset));
      org.junit.jupiter.api.Assertions.assertArrayEquals(png, screens.asset(hash).content());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              screens.upload(
                  session.token(),
                  "desktop",
                  new SalesScreens.Asset(
                      UUID.randomUUID(),
                      "0".repeat(64),
                      "image/svg+xml",
                      "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
      var doc = ScreenDocuments.starter(screens.store(), screens.catalog());
      String malformed =
          context
              .getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
              .writeValueAsString(new SalesScreens.Save(UUID.randomUUID(), 0, "Broken", false, doc))
              .replace("ORDER_LIST", "EXECUTE_SQL");
      var rejected =
          http.send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "save"))
                  .header("Authorization", "Bearer " + identity.token())
                  .header("X-Screen-Terminal", "desktop")
                  .header("X-Screen-Session", session.token())
                  .header("Content-Type", "application/json")
                  .POST(java.net.http.HttpRequest.BodyPublishers.ofString(malformed))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(400, rejected.statusCode());
    }
  }

  @Test
  void concurrentAuthorsCannotOverwriteAnUnseenRevision() throws Exception {
    var fixture = new H2LocalAuthenticationTest();
    fixture.directory = directory;
    try (var context = fixture.start()) {
      fixture.setup(context);
      var auth = context.getBean(H2LocalAuthentication.class);
      var screens = context.getBean(ScreenService.class);
      UUID owner =
          UUID.fromString(
              auth.bootstrapOwner(UUID.randomUUID(), "concurrent.owner", "102938".toCharArray())
                  .path("staffId")
                  .asText());
      var first =
          auth.enter("102938".toCharArray(), owner, PosModule.ADMINISTRATION, "terminal", true);
      var created =
          auth.createStaff(
              first.token(),
              "terminal",
              UUID.randomUUID(),
              "second.manager",
              "MANAGER",
              "564738".toCharArray());
      var second =
          auth.enter(
              "564738".toCharArray(),
              UUID.fromString(created.path("staffId").asText()),
              PosModule.ADMINISTRATION,
              "terminal",
              false);
      var doc = ScreenDocuments.starter(screens.store(), screens.catalog());
      try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var ready = new java.util.concurrent.CountDownLatch(1);
        var jobs = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (String session : java.util.List.of(first.token(), second.token()))
          jobs.add(
              workers.submit(
                  () -> {
                    ready.await();
                    try {
                      screens.save(
                          session,
                          "terminal",
                          new SalesScreens.Save(UUID.randomUUID(), 0, "Concurrent", false, doc));
                      return 200;
                    } catch (LocalFailure conflict) {
                      return conflict.status();
                    }
                  }));
        ready.countDown();
        var statuses = new java.util.ArrayList<Integer>();
        for (var job : jobs) statuses.add(job.get(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(java.util.Set.of(200, 409), java.util.Set.copyOf(statuses));
      }
      assertEquals(1, screens.revision(doc.screenId(), 1).revision());
    }
  }
}

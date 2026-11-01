package za.co.onlinepos.server.screen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.ScreenValidation;
import za.co.onlinepos.server.dev.DevCatalogueService;
import za.co.onlinepos.server.dev.H2LocalAuthentication;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.ItemCustomizationService;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.ModuleAccess;

@Service
@Profile({"installed", "local-store", "dev"})
public class ScreenService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate tx;
  private final boolean dev;
  private final String schema;
  private final ObjectProvider<ModuleAccess> modules;
  private final ObjectProvider<H2LocalAuthentication> authentication;
  private final ObjectProvider<DevCatalogueService> developmentCatalog;
  private final ObjectProvider<ItemCustomizationService> installedCatalog;

  record Actor(UUID id, String terminal) {}

  public ScreenService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      TransactionTemplate tx,
      Environment env,
      ObjectProvider<ModuleAccess> modules,
      ObjectProvider<H2LocalAuthentication> authentication,
      ObjectProvider<DevCatalogueService> developmentCatalog,
      ObjectProvider<ItemCustomizationService> installedCatalog) {
    this.jdbc = jdbc;
    this.json =
        json.copy()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.tx = tx;
    this.dev = env.matchesProfiles("dev");
    schema = dev ? "dev_meta" : "pos";
    this.modules = modules;
    this.authentication = authentication;
    this.developmentCatalog = developmentCatalog;
    this.installedCatalog = installedCatalog;
  }

  int branch() {
    var id =
        jdbc.queryForObject(
            dev
                ? "SELECT branch_id FROM dev_meta.local_scope WHERE singleton=1"
                : "SELECT branch_id FROM pos.store_identity",
            Integer.class);
    if (id == null) throw new LocalFailure(409, "Configure the store first");
    return id;
  }

  public UUID store() {
    return jdbc.queryForObject(
        dev
            ? "SELECT store_uuid FROM public.branch WHERE branch_id=?"
            : "SELECT store_uuid FROM pos.store_identity WHERE branch_id=?",
        UUID.class,
        branch());
  }

  void lock() {
    jdbc.queryForObject(
        dev
            ? "SELECT next_id FROM dev_meta.category_ids WHERE singleton=1 FOR UPDATE"
            : "SELECT position FROM pos.stream_head WHERE branch_id=" + branch() + " FOR UPDATE",
        Long.class);
  }

  public ItemCustomization.Catalog catalog() {
    return dev
        ? developmentCatalog.getObject().catalog()
        : installedCatalog.getObject().catalog(false);
  }

  private Actor require(String token, String terminal, String permission) {
    if (dev) {
      var s = authentication.getObject().authenticate(token, terminal, PosModule.ADMINISTRATION);
      if (!s.permissions().contains(permission)
          || !s.permissions().contains("module.sales_screen_builder"))
        throw new LocalFailure(403, "Screen authoring permission required");
      return new Actor(s.staffId(), terminal);
    }
    var s = modules.getObject().authenticate(token, terminal, false);
    boolean owner =
        jdbc.queryForObject(
                "SELECT count(*) FROM pos.store_owner WHERE branch_id=? AND staff_id=?",
                Integer.class,
                branch(),
                s.staffId())
            == 1;
    boolean manager =
        jdbc.queryForObject(
                "SELECT count(*) FROM pos.staff WHERE branch_id=? AND staff_id=? AND role='MANAGER' AND enabled",
                Integer.class,
                branch(),
                s.staffId())
            == 1;
    if (s.module() != PosModule.ADMINISTRATION
        || !(owner || manager)
        || !owner
            && (!s.permissions().contains(permission)
                || !s.permissions().contains("module.sales_screen_builder")))
      throw new LocalFailure(403, "Qualified Administration session required");
    return new Actor(s.staffId(), terminal);
  }

  public Object access(String pin, UUID staff, String terminal) {
    if (!dev) throw new LocalFailure(403, "Use Administration staff access");
    char[] value = pin == null ? new char[0] : pin.toCharArray();
    try {
      var session =
          authentication.getObject().enter(value, staff, PosModule.ADMINISTRATION, terminal, false);
      require(session.token(), terminal, "sales_screen.edit");
      return Map.of("token", session.token(), "username", session.username());
    } finally {
      java.util.Arrays.fill(value, '\0');
    }
  }

  private String encode(Object o) {
    try {
      return json.writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid screen JSON");
    }
  }

  private <T> T decode(String s, Class<T> type) {
    try {
      return json.readerFor(type)
          .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .readValue(s);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid screen document");
    }
  }

  private String table(String name) {
    return schema + "." + name;
  }

  public Object context(String token, String terminal) {
    return tx.execute(
        s -> {
          lock();
          require(token, terminal, "sales_screen.edit");
          return Map.of(
              "storeId",
              store(),
              "catalog",
              catalog(),
              "capabilities",
              SalesScreens.draftCapabilities(),
              "definitions",
              jdbc.queryForList(
                  "SELECT screen_id,name,template,revision FROM "
                      + table("screen_definition")
                      + " WHERE branch_id=? ORDER BY name",
                  branch()));
        });
  }

  public SalesScreens.Revision revision(UUID id, long revision) {
    var rows =
        jdbc.queryForList(
            "SELECT d.name,d.template,r.document_text,r.document_hash FROM "
                + table("screen_revision")
                + " r JOIN "
                + table("screen_definition")
                + " d ON d.screen_id=r.screen_id WHERE d.branch_id=? AND r.screen_id=? AND r.revision=?",
            branch(),
            id,
            revision);
    if (rows.size() != 1) throw new LocalFailure(404, "Screen revision not found");
    var row = rows.getFirst();
    return new SalesScreens.Revision(
        id,
        revision,
        (String) row.get("name"),
        (Boolean) row.get("template"),
        row.get("document_hash").toString(),
        decode(text(row.get("document_text")), SalesScreens.Document.class));
  }

  private String text(Object value) {
    if (value instanceof java.sql.Clob c)
      try {
        return c.getSubString(1, (int) c.length());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    return value.toString();
  }

  public Object history(String token, String terminal, UUID id) {
    return tx.execute(
        s -> {
          lock();
          require(token, terminal, "sales_screen.edit");
          return jdbc.queryForList(
              "SELECT r.revision,r.document_hash,r.created_at FROM "
                  + table("screen_revision")
                  + " r JOIN "
                  + table("screen_definition")
                  + " d ON d.screen_id=r.screen_id WHERE d.branch_id=? AND r.screen_id=? ORDER BY r.revision DESC",
              branch(),
              id);
        });
  }

  public SalesScreens.Revision authorRevision(String token, String terminal, UUID id, long rev) {
    return tx.execute(
        s -> {
          lock();
          require(token, terminal, "sales_screen.edit");
          return revision(id, rev);
        });
  }

  private List<String> validate(SalesScreens.Document document, boolean publish) {
    var c = catalog();
    var prices =
        c.products().entrySet().stream()
            .filter(e -> !publish || e.getValue().available())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    String value = encode(document);
    if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > ScreenValidation.Limits.standard().bytes())
      throw new IllegalArgumentException("Screen document is too large");
    var warnings =
        ScreenValidation.validate(document, store(), prices, ScreenValidation.Limits.standard());
    for (var p : document.pages())
      for (var w : p.widgets())
        if (w.style().assetHash() != null
            && jdbc.queryForObject(
                    "SELECT count(*) FROM "
                        + table("screen_asset")
                        + " WHERE branch_id=? AND asset_hash=?",
                    Integer.class,
                    branch(),
                    w.style().assetHash())
                != 1) throw new IllegalArgumentException("Missing screen asset");
    return warnings;
  }

  public Object preview(String token, String terminal, SalesScreens.Document document) {
    return tx.execute(
        s -> {
          lock();
          require(token, terminal, "sales_screen.edit");
          return Map.of(
              "warnings",
              validate(document, false),
              "capabilities",
              SalesScreens.draftCapabilities());
        });
  }

  public JsonNode save(String token, String terminal, SalesScreens.Save save) {
    return tx.execute(
        s -> {
          lock();
          var actor =
              require(
                  token,
                  terminal,
                  save.template() ? "sales_screen.manage_templates" : "sales_screen.edit");

          if (save.name() == null
              || save.name().isBlank()
              || save.name().length() > 160
              || save.expectedRevision() < 0)
            throw new IllegalArgumentException("Screen name and expected revision required");
          var body =
              Map.<String, Object>of(
                  "screenId",
                  save.document().screenId(),
                  "expectedRevision",
                  save.expectedRevision(),
                  "name",
                  save.name(),
                  "template",
                  save.template(),
                  "document",
                  encode(save.document()));
          var prior = receipt(save.commandId(), actor, "SAVE", body);
          if (prior != null) return prior;
          validate(save.document(), false);
          return command(
              token,
              save.commandId(),
              actor,
              "SAVE",
              body,
              () -> {
                var found =
                    jdbc.queryForList(
                        "SELECT revision,template FROM "
                            + table("screen_definition")
                            + " WHERE screen_id=? AND branch_id=?",
                        save.document().screenId(),
                        branch());
                long current =
                    found.isEmpty() ? 0 : ((Number) found.getFirst().get("revision")).longValue();
                if (current != save.expectedRevision()
                    || !found.isEmpty()
                        && (Boolean) found.getFirst().get("template") != save.template())
                  throw new LocalFailure(
                      409, "Screen changed; retain the draft and reload before retrying");
                if (found.isEmpty())
                  jdbc.update(
                      "INSERT INTO " + table("screen_definition") + " VALUES(?,?,?,?,0)",
                      save.document().screenId(),
                      branch(),
                      save.name(),
                      save.template());
                long next = current + 1;
                String doc = encode(save.document()), hash = CommandJournal.digest(doc);
                jdbc.update(
                    "INSERT INTO "
                        + table("screen_revision")
                        + "(screen_id,revision,document_text,document_hash,actor_id) VALUES(?,?,?,?,?)",
                    save.document().screenId(),
                    next,
                    doc,
                    hash,
                    actor.id());
                jdbc.update(
                    "UPDATE "
                        + table("screen_definition")
                        + " SET revision=?,name=? WHERE screen_id=?",
                    next,
                    save.name(),
                    save.document().screenId());
                return json.valueToTree(
                    Map.of("screenId", save.document().screenId(), "revision", next, "hash", hash));
              });
        });
  }

  public JsonNode publish(
      String token, String terminal, SalesScreens.Publish publish, boolean rollback) {
    return tx.execute(
        s -> {
          lock();
          var actor = require(token, terminal, "sales_screen.publish");
          var body =
              Map.<String, Object>of(
                  "screenId",
                  publish.screenId(),
                  "revision",
                  publish.revision(),
                  "expectedRevision",
                  publish.expectedSequence());
          var prior = receipt(publish.commandId(), actor, rollback ? "ROLLBACK" : "PUBLISH", body);
          if (prior != null) return prior;
          var revision = revision(publish.screenId(), publish.revision());
          if (revision.template())
            throw new IllegalArgumentException("Templates cannot be published as a store screen");
          validate(revision.document(), true);
          return command(
              token,
              publish.commandId(),
              actor,
              rollback ? "ROLLBACK" : "PUBLISH",
              body,
              () -> {
                var rows =
                    jdbc.queryForList(
                        "SELECT sequence FROM "
                            + table("store_screen_publication")
                            + " WHERE branch_id=?",
                        branch());
                long current =
                    rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("sequence")).longValue();
                if (current != publish.expectedSequence())
                  throw new LocalFailure(409, "Publication changed; reload before publishing");
                var event = UUID.randomUUID();
                long next = current + 1;
                if (rows.isEmpty())
                  jdbc.update(
                      "INSERT INTO " + table("store_screen_publication") + " VALUES(?,?,?,?,?)",
                      branch(),
                      store(),
                      next,
                      publish.screenId(),
                      publish.revision());
                else
                  jdbc.update(
                      "UPDATE "
                          + table("store_screen_publication")
                          + " SET sequence=?,screen_id=?,revision=? WHERE branch_id=?",
                      next,
                      publish.screenId(),
                      publish.revision(),
                      branch());
                jdbc.update(
                    "INSERT INTO "
                        + table("screen_publication_event")
                        + "(event_id,branch_id,store_id,sequence,screen_id,revision,document_hash) VALUES(?,?,?,?,?,?,?)",
                    event,
                    branch(),
                    store(),
                    next,
                    publish.screenId(),
                    publish.revision(),
                    revision.hash());
                return json.valueToTree(
                    Map.of(
                        "eventId",
                        event,
                        "screenId",
                        publish.screenId(),
                        "revision",
                        publish.revision(),
                        "sequence",
                        next,
                        "hash",
                        revision.hash()));
              });
        });
  }

  private JsonNode receipt(UUID id, Actor actor, String operation, Map<String, Object> body) {
    CommandJournal.requireUuid(id);
    var rows =
        jdbc.queryForList(
            "SELECT branch_id,actor_id,terminal_identity,payload_hash,result FROM "
                + table("screen_command")
                + " WHERE command_id=?",
            id);
    if (rows.isEmpty()) return null;
    String hash =
        dev
            ? CommandJournal.digest(operation + ":" + encode(body))
            : jdbc.queryForObject(
                "SELECT encode(sha256(convert_to(?||?::jsonb::text,'UTF8')),'hex')",
                String.class,
                operation,
                encode(body));
    var old = rows.getFirst();
    if (((Number) old.get("branch_id")).intValue() != branch()
        || !actor.id().equals(old.get("actor_id"))
        || !actor.terminal().equals(old.get("terminal_identity"))
        || !hash.equals(old.get("payload_hash")))
      throw new LocalFailure(409, "Command identity was reused");
    return decode(text(old.get("result")), JsonNode.class);
  }

  private JsonNode command(
      String token,
      UUID id,
      Actor actor,
      String operation,
      Map<String, Object> body,
      java.util.function.Supplier<JsonNode> mutation) {
    CommandJournal.requireUuid(id);
    if (!dev)
      return jdbc.queryForObject(
          "SELECT pos.write_sales_screen(?,?,?,?,?::jsonb)::text",
          (rs, n) -> decode(rs.getString(1), JsonNode.class),
          CommandJournal.digest(token),
          actor.terminal(),
          id,
          operation,
          encode(body));
    String hash = CommandJournal.digest(operation + ":" + encode(body));
    var receipts =
        jdbc.queryForList(
            "SELECT actor_id,terminal_identity,payload_hash,result FROM dev_meta.screen_command WHERE command_id=?",
            id);
    if (!receipts.isEmpty()) {
      var old = receipts.getFirst();
      if (!actor.id().equals(old.get("actor_id"))
          || !actor.terminal().equals(old.get("terminal_identity"))
          || !hash.equals(old.get("payload_hash")))
        throw new LocalFailure(409, "Command identity was reused");
      return decode(text(old.get("result")), JsonNode.class);
    }
    var result = decode(encode(mutation.get()), JsonNode.class);
    UUID event = UUID.randomUUID();
    String action =
        !operation.equals("PUBLISH") && !operation.equals("ROLLBACK")
            ? "SALES_SCREEN_CHANGED"
            : "SALES_SCREEN_PUBLISHED";
    String audit =
        encode(
            Map.of(
                "actorId",
                actor.id(),
                "terminal",
                actor.terminal(),
                "operation",
                operation,
                "result",
                result));
    jdbc.update(
        "INSERT INTO dev_meta.module_events(event_id,command_id,action,entity_id,revision,payload) VALUES(?,?,?,?,?,?)",
        event,
        id,
        action,
        UUID.fromString(body.get("screenId").toString()),
        result.path("revision").asLong(),
        audit);
    jdbc.update("INSERT INTO dev_meta.outbox(event_id,body) VALUES(?,?)", event, audit);
    jdbc.update(
        "INSERT INTO dev_meta.screen_command VALUES(?,?,?,?,?,?)",
        id,
        branch(),
        actor.id(),
        actor.terminal(),
        hash,
        encode(result));
    return result;
  }

  public SalesScreens.Workflow approvedWorkflow(
      String token, String terminal, SalesScreens.WorkflowRequest request) {
    return tx.execute(
        s -> {
          lock();
          if (!dev) {
            var session = modules.getObject().authenticate(token, terminal, false);
            if (session.module() != PosModule.SALES_ORDERS
                || !session.permissions().contains("module.sales_orders"))
              throw new LocalFailure(403, "Sales session required");
          }
          var rows =
              jdbc.queryForList(
                  "SELECT screen_id,revision FROM "
                      + table("store_screen_publication")
                      + " WHERE branch_id=?",
                  branch());
          if (rows.size() != 1
              || !request.screenId().equals(rows.getFirst().get("screen_id"))
              || request.revision() != ((Number) rows.getFirst().get("revision")).longValue())
            throw new LocalFailure(
                409, "Workflow publication changed; finish this draft before updating layout");
          var doc = revision(request.screenId(), request.revision()).document();
          validate(doc, true);
          var flow =
              doc.workflows().stream()
                  .filter(f -> f.id().equals(request.workflowId()))
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("Unknown workflow"));
          for (var step : flow.steps())
            if (step.type() == SalesScreens.NodeType.ACTION) {
              if (!SalesScreens.draftCapabilities().available().contains(step.action()))
                throw new LocalFailure(409, "Workflow action has no authoritative backend");
              if (java.util.Set.of(
                      SalesScreens.Action.SAVE_DRAFT,
                      SalesScreens.Action.OPEN_DRAFT,
                      SalesScreens.Action.EDIT_LINE,
                      SalesScreens.Action.SET_QUANTITY,
                      SalesScreens.Action.SELECT_CUSTOMER)
                  .contains(step.action())) {
                var next =
                    flow.steps().stream()
                        .filter(n -> n.id().equals(step.next()))
                        .findFirst()
                        .orElseThrow();
                if (next.type() != SalesScreens.NodeType.END)
                  throw new IllegalArgumentException(
                      "Interactive draft actions must end the workflow");
              }
            }
          return flow;
        });
  }

  public SalesScreens.Asset asset(String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid asset hash");
    return jdbc
        .query(
            "SELECT media_type,content FROM "
                + table("screen_asset")
                + " WHERE branch_id=? AND asset_hash=?",
            (rs, n) -> new SalesScreens.Asset(null, hash, rs.getString(1), rs.getBytes(2)),
            branch(),
            hash)
        .stream()
        .findFirst()
        .orElseThrow(() -> new LocalFailure(404, "Missing screen asset"));
  }

  public JsonNode upload(String token, String terminal, SalesScreens.Asset asset) {
    return tx.execute(
        s -> {
          lock();
          var actor = require(token, terminal, "sales_screen.edit");
          byte[] content = asset.content();
          if (content == null
              || content.length == 0
              || content.length > 524288
              || !java.util.Set.of("image/png", "image/jpeg").contains(asset.mediaType()))
            throw new IllegalArgumentException("Use a PNG or JPEG up to 512 KB");
          String hash;
          try {
            hash =
                java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
            try (var input =
                javax.imageio.ImageIO.createImageInputStream(
                    new java.io.ByteArrayInputStream(content))) {
              var readers = javax.imageio.ImageIO.getImageReaders(input);
              if (!readers.hasNext()) throw new IllegalArgumentException("Invalid image");
              var reader = readers.next();
              try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!(format.equalsIgnoreCase("png") && asset.mediaType().equals("image/png")
                        || format.equalsIgnoreCase("jpeg")
                            && asset.mediaType().equals("image/jpeg"))
                    || reader.getWidth(0) > 4096
                    || reader.getHeight(0) > 4096)
                  throw new IllegalArgumentException("Image type mismatch or excessive dimensions");
                reader.read(0);
              } finally {
                reader.dispose();
              }
            }
          } catch (IllegalArgumentException e) {
            throw e;
          } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid image");
          }
          if (!hash.equals(asset.hash())) throw new IllegalArgumentException("Asset hash mismatch");
          var body =
              Map.<String, Object>of(
                  "screenId",
                  UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                  "hash",
                  hash,
                  "mediaType",
                  asset.mediaType(),
                  "content",
                  java.util.Base64.getEncoder().encodeToString(content));
          return command(
              token,
              asset.commandId(),
              actor,
              "ASSET",
              body,
              () -> {
                var found =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM "
                            + table("screen_asset")
                            + " WHERE branch_id=? AND asset_hash=?",
                        Integer.class,
                        branch(),
                        hash);
                if (found == 0)
                  jdbc.update(
                      "INSERT INTO " + table("screen_asset") + " VALUES(?,?,?,?)",
                      hash,
                      branch(),
                      asset.mediaType(),
                      content);
                return json.valueToTree(Map.of("hash", hash));
              });
        });
  }

  public Object published() {
    return tx.execute(
        s -> {
          lock();
          var rows =
              jdbc.queryForList(
                  "SELECT p.sequence,p.screen_id,p.revision,e.event_id FROM "
                      + table("store_screen_publication")
                      + " p JOIN "
                      + table("screen_publication_event")
                      + " e ON e.branch_id=p.branch_id AND e.sequence=p.sequence WHERE p.branch_id=?",
                  branch());
          if (rows.isEmpty())
            return Map.of(
                "storeId",
                store(),
                "sequence",
                0,
                "capabilities",
                SalesScreens.draftCapabilities());
          return envelope(rows.getFirst());
        });
  }

  private SalesScreens.Publication envelope(Map<String, Object> row) {
    UUID id = (UUID) row.get("screen_id");
    long version = ((Number) row.get("revision")).longValue();
    var r = revision(id, version);
    return new SalesScreens.Publication(
        (UUID) row.get("event_id"),
        store(),
        ((Number) row.get("sequence")).longValue(),
        id,
        version,
        r.hash(),
        r.document());
  }

  public Object events(long after) {
    if (after < 0) throw new IllegalArgumentException("Invalid configuration sequence");
    return tx.execute(
        s -> {
          lock();
          return jdbc
              .queryForList(
                  "SELECT event_id,sequence,screen_id,revision FROM "
                      + table("screen_publication_event")
                      + " WHERE branch_id=? AND sequence>? ORDER BY sequence LIMIT 100",
                  branch(),
                  after)
              .stream()
              .map(this::envelope)
              .toList();
        });
  }
}

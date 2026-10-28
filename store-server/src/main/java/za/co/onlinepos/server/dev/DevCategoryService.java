package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.DevData;

@Service
@Profile("dev")
public class DevCategoryService {
  public static final class Failure extends RuntimeException {
    private final int status;
    private final String field;
    private final String code;

    Failure(int status, String code, String field, String message) {
      super(message);
      this.status = status;
      this.field = field;
      this.code = code;
    }

    public int status() {
      return status;
    }

    public String field() {
      return field;
    }

    public String code() {
      return code;
    }
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper json;

  public DevCategoryService(
      JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper json) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.json = json;
  }

  private static DevData.Category row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
    return new DevData.Category(
        rs.getInt("id"),
        rs.getString("name"),
        rs.getInt("sort_order"),
        rs.getBoolean("active"),
        rs.getLong("revision"),
        rs.getBoolean("archived"));
  }

  public DevData.Page list(String search, String state, int page, int size) {
    if (search.length() > 200
        || page < 0
        || page > 1000000
        || size < 1
        || size > 100
        || !java.util.Set.of("active", "disabled", "archived", "all").contains(state))
      throw new Failure(400, "VALIDATION", "search", "Invalid search or page");
    String filter =
        " WHERE LOCATE(LOWER(?),LOWER(name))>0"
            + (state.equals("all")
                ? ""
                : state.equals("archived")
                    ? " AND archived=TRUE"
                    : " AND archived=FALSE AND active=" + state.equals("active"));
    long total =
        jdbc.queryForObject(
            "SELECT count(*) FROM public.menu_category" + filter, Long.class, search);
    var records =
        jdbc.query(
            "SELECT id,name,sort_order,active,revision,archived FROM public.menu_category"
                + filter
                + " ORDER BY sort_order,id LIMIT ? OFFSET ?",
            DevCategoryService::row,
            search,
            size,
            (long) page * size);
    return new DevData.Page(records, total, page, size);
  }

  public DevData.Category save(Integer id, DevData.Save command) {
    if (command.commandId() == null)
      throw new Failure(400, "VALIDATION", "commandId", "Command identity required");
    if (command.name() == null
        || command.name().strip().isEmpty()
        || command.name().strip().length() > 200)
      throw new Failure(400, "VALIDATION", "name", "Enter a name of 1–200 characters");
    if (command.sortOrder() == null)
      throw new Failure(400, "VALIDATION", "sortOrder", "Enter an integer sort order");
    if (command.active() == null)
      throw new Failure(400, "VALIDATION", "active", "Active status required");
    if (id != null && (command.expectedRevision() == null || command.expectedRevision() < 0))
      throw new Failure(400, "VALIDATION", "revision", "Revision required");
    if (id == null && command.expectedRevision() != null)
      throw new Failure(400, "VALIDATION", "revision", "New category cannot have a revision");
    String digest;
    try {
      digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          json.writeValueAsBytes(
                              java.util.Arrays.asList(
                                  id,
                                  command.expectedRevision(),
                                  command.name(),
                                  command.sortOrder(),
                                  command.archived() == null
                                      ? command.active()
                                      : java.util.List.of(command.active(), command.archived())))));
    } catch (Exception failure) {
      throw new IllegalStateException("Cannot encode category command");
    }
    return transactions.execute(
        status -> {
          long next =
              jdbc.queryForObject(
                  "SELECT next_id FROM dev_meta.category_ids WHERE singleton=1 FOR UPDATE",
                  Long.class);
          var receipts =
              jdbc.query(
                  "SELECT digest,result_json FROM dev_meta.commands WHERE command_id=?",
                  (rs, n) ->
                      java.util.Map.of("digest", rs.getString(1), "result_json", rs.getString(2)),
                  command.commandId());
          if (!receipts.isEmpty()) {
            if (!digest.equals(receipts.getFirst().get("digest")))
              throw new Failure(
                  409, "COMMAND_REUSED", "", "Command was already used for another change");
            try {
              return json.readValue(
                  receipts.getFirst().get("result_json").toString(), DevData.Category.class);
            } catch (Exception failure) {
              throw new IllegalStateException("Cannot read command receipt");
            }
          }
          String name = command.name().strip();
          String duplicateSql =
              "SELECT count(*) FROM public.menu_category WHERE LOWER(TRIM(name))=?";
          long duplicates =
              id == null
                  ? jdbc.queryForObject(duplicateSql, Long.class, name.toLowerCase(Locale.ROOT))
                  : jdbc.queryForObject(
                      duplicateSql + " AND id<>?", Long.class, name.toLowerCase(Locale.ROOT), id);
          if (duplicates > 0)
            throw new Failure(409, "DUPLICATE", "name", "A category with this name already exists");
          DevData.Category saved;
          if (id == null) {
            if (next > Integer.MAX_VALUE)
              throw new Failure(409, "ID_EXHAUSTED", "", "No more category IDs available");
            int newId = (int) next;
            jdbc.update("UPDATE dev_meta.category_ids SET next_id=? WHERE singleton=1", next + 1);
            jdbc.update(
                "INSERT INTO public.menu_category(id,name,sort_order,active,revision,archived) VALUES(?,?,?,?,0,?)",
                newId,
                name,
                command.sortOrder(),
                Boolean.TRUE.equals(command.archived()) ? false : command.active(),
                Boolean.TRUE.equals(command.archived()));
            saved =
                new DevData.Category(
                    newId,
                    name,
                    command.sortOrder(),
                    Boolean.TRUE.equals(command.archived()) ? false : command.active(),
                    0,
                    Boolean.TRUE.equals(command.archived()));
          } else {
            int changed =
                jdbc.update(
                    "UPDATE public.menu_category SET name=?,sort_order=?,active=?,archived=COALESCE(?,archived),revision=revision+1 WHERE id=? AND revision=?",
                    name,
                    command.sortOrder(),
                    Boolean.TRUE.equals(command.archived()) ? false : command.active(),
                    command.archived(),
                    id,
                    command.expectedRevision());
            if (changed != 1)
              throw new Failure(
                  409,
                  "REVISION_CONFLICT",
                  "",
                  "Category changed or was removed; reload before saving");
            saved =
                jdbc.queryForObject(
                    "SELECT * FROM public.menu_category WHERE id=?", DevCategoryService::row, id);
          }
          try {
            jdbc.update(
                "INSERT INTO dev_meta.commands VALUES(?,?,?)",
                command.commandId(),
                digest,
                json.writeValueAsString(saved));
          } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode receipt");
          }
          jdbc.update(
              "INSERT INTO dev_meta.changes(change_id,command_id,category_id,action,revision) VALUES(?,?,?,?,?)",
              UUID.randomUUID(),
              command.commandId(),
              saved.id(),
              id == null
                  ? "CREATED"
                  : saved.archived() ? "ARCHIVED" : saved.active() ? "UPDATED" : "DISABLED",
              saved.revision());
          var modules = new DevModuleStore(jdbc, json, transactions);
          long catalogueRevision = modules.bump();
          modules.event(
              command.commandId(),
              "CATALOGUE_CHANGED",
              DevModuleStore.identity("menu_category", Integer.toString(saved.id())),
              catalogueRevision,
              java.util.Map.of("tables", java.util.List.of("menu_category"), "recordsChanged", 1));
          modules.queueRecord(command.commandId(), saved);
          return saved;
        });
  }
}

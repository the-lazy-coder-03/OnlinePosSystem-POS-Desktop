package za.co.onlinepos.server.dev;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.Branch;

/** Local configuration only. No cloud client or remote directory is required. */
@Service
@Profile("dev")
public class DevStoreService {
  private final DevModuleStore store;

  public DevStoreService(DevModuleStore store) {
    this.store = store;
  }

  public record Setup(UUID commandId, String businessName, String storeName, String code) {}

  public record Edit(
      UUID commandId,
      long expectedRevision,
      String name,
      String code,
      String address,
      String phone,
      String email,
      boolean active,
      String businessName) {}

  public Object state() {
    return store.tx.execute(
        status -> {
          store.lock();
          var id =
              store.jdbc.queryForObject(
                  "SELECT branch_id FROM dev_meta.local_scope WHERE singleton=1", Integer.class);
          if (id == null)
            return Map.of(
                "configured",
                false,
                "integrationEnabled",
                false,
                "stores",
                store.jdbc.queryForList(
                    "SELECT branch_id,name FROM public.branch ORDER BY branch_id"));
          var profile =
              store.jdbc.queryForMap(
                  "SELECT branch_id,store_uuid,name,code,address,phone,email,active,archived,revision FROM public.branch WHERE branch_id=?",
                  id);
          var business =
              store.jdbc.queryForList(
                  "SELECT business_id,name,revision FROM dev_meta.business WHERE singleton=1");
          return Map.of(
              "configured",
              true,
              "store",
              profile,
              "business",
              business,
              "integrationEnabled",
              false);
        });
  }

  public Object setup(Setup request) {
    String business = label(request.businessName()),
        name = label(request.storeName()),
        code = label(request.code());
    if (code.length() > 40) throw DevModuleStore.fail(400, "Store code must be 1–40 characters");
    return store.command(
        request.commandId(),
        "LOCAL_STORE_SETUP",
        request,
        () -> {
          if (store.jdbc.queryForObject(
                  "SELECT branch_id FROM dev_meta.local_scope WHERE singleton=1", Integer.class)
              != null) throw DevModuleStore.fail(409, "This Master is already assigned to a store");
          if (store.jdbc.queryForObject("SELECT count(*) FROM public.branch", Integer.class) > 0)
            throw DevModuleStore.fail(
                409,
                "This database contains existing stores. Select the intended store explicitly; a new business requires its own database.");
          if (!store.jdbc.queryForList("SELECT business_id FROM dev_meta.business").isEmpty())
            throw DevModuleStore.fail(409, "Existing business requires explicit identity adoption");
          long id =
              store.jdbc.queryForObject(
                  "SELECT next_id FROM dev_meta.store_ids WHERE singleton=1 FOR UPDATE",
                  Long.class);
          if (id > Integer.MAX_VALUE)
            throw DevModuleStore.fail(409, "Store identity range exhausted");
          UUID businessId = UUID.randomUUID(), storeId = UUID.randomUUID();
          store.jdbc.update(
              "INSERT INTO dev_meta.business(singleton,business_id,name,created_at,updated_at) VALUES(1,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
              businessId,
              business);
          store.jdbc.update(
              "INSERT INTO public.branch(branch_id,store_uuid,name,code,created_at,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
              id,
              storeId,
              name,
              code);
          store.jdbc.update("UPDATE dev_meta.store_ids SET next_id=? WHERE singleton=1", id + 1);
          store.jdbc.update("UPDATE dev_meta.local_scope SET branch_id=? WHERE singleton=1", id);
          store.event(
              request.commandId(),
              "LOCAL_STORE_CREATED",
              storeId,
              0,
              Map.of("branchId", id, "businessId", businessId));
          return store.tree(state());
        });
  }

  public Object edit(Edit request) {
    label(request.name());
    label(request.code());
    if (request.code().strip().length() > 40)
      throw DevModuleStore.fail(400, "Store code must be 1–40 characters");
    if (request.address() == null
        || request.address().length() > 2000
        || request.phone() == null
        || request.phone().length() > 64
        || request.email() == null
        || request.email().length() > 254)
      throw DevModuleStore.fail(400, "Invalid store contact details");
    return store.command(
        request.commandId(),
        "LOCAL_STORE_EDIT",
        request,
        () -> {
          int id = store.branchId();
          if (store.jdbc.update(
                  "UPDATE public.branch SET name=?,code=?,address=?,phone=?,email=?,active=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE branch_id=? AND revision=?",
                  request.name().strip(),
                  request.code().strip(),
                  request.address(),
                  request.phone(),
                  request.email(),
                  request.active(),
                  id,
                  request.expectedRevision())
              != 1) throw DevModuleStore.fail(409, "Store details changed; reload before editing");
          if (request.businessName() != null) {
            label(request.businessName());
            store.jdbc.update(
                "UPDATE dev_meta.business SET name=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE singleton=1",
                request.businessName().strip());
          }
          store.event(
              request.commandId(),
              "LOCAL_STORE_CHANGED",
              store.jdbc.queryForObject(
                  "SELECT store_uuid FROM public.branch WHERE branch_id=?", UUID.class, id),
              request.expectedRevision() + 1,
              Map.of("branchId", id));
          return store.tree(state());
        });
  }

  /** Explicit legacy selection; no name-based or minimum-ID guessing. */
  public Object adopt(UUID commandId, int branchId, String businessName) {
    Branch.fromId(branchId);
    label(businessName);
    return store.command(
        commandId,
        "LOCAL_STORE_ADOPT",
        java.util.List.of(branchId, businessName),
        () -> {
          if (store.jdbc.queryForObject(
                  "SELECT branch_id FROM dev_meta.local_scope WHERE singleton=1", Integer.class)
              != null)
            throw DevModuleStore.fail(
                409, "Master already assigned; terminal reassignment is not an edit");
          var ids =
              store.jdbc.queryForList(
                  "SELECT store_uuid FROM public.branch WHERE branch_id=?", UUID.class, branchId);
          if (ids.isEmpty()) throw DevModuleStore.fail(400, "Unknown existing store");
          store.jdbc.update(
              "INSERT INTO dev_meta.business(singleton,business_id,name,created_at,updated_at) VALUES(1,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
              UUID.randomUUID(),
              businessName.strip());
          store.jdbc.update(
              "UPDATE dev_meta.local_scope SET branch_id=? WHERE singleton=1", branchId);
          store.event(
              commandId, "LOCAL_STORE_ADOPTED", ids.getFirst(), 0, Map.of("branchId", branchId));
          return store.tree(state());
        });
  }

  private static String label(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 200
        || value.codePoints().anyMatch(Character::isISOControl))
      throw DevModuleStore.fail(400, "A name/code of 1–200 characters is required");
    return value.strip();
  }
}

package za.co.onlinepos.contracts;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Customer-only transport boundary. No production connector or scheduler is registered yet. */
public final class CustomerSync {
  private CustomerSync() {}

  public static final Set<String> ADDRESS =
      Set.of(
          "house_number",
          "street",
          "area",
          "complex_name",
          "city",
          "postal_code",
          "google_place_id",
          "formatted_address",
          "latitude",
          "longitude",
          "province",
          "country");

  public record Snapshot(
      UUID globalId,
      Long cloudId,
      int preferredBranch,
      Map<String, String> fields,
      Map<String, Instant> fieldTimes,
      boolean banned,
      String banReason,
      Instant banChangedAt) {
    public Snapshot {
      if (globalId == null
          || globalId.equals(new UUID(0, 0))
          || fieldTimes == null
          || preferredBranch < 1
          || banned && (banReason == null || banReason.isBlank()))
        throw new IllegalArgumentException("Invalid customer identity");
      fields = Map.copyOf(Customers.validate(fields));
      fieldTimes = Map.copyOf(fieldTimes);
      banReason = banReason == null ? "" : banReason;
      if (!fieldTimes
              .keySet()
              .containsAll(
                  Set.of(
                      "first_name",
                      "last_name",
                      "phone1",
                      "phone2",
                      "email",
                      "address",
                      "preferred_store"))
          || banChangedAt == null)
        throw new IllegalArgumentException("Server field timestamps required");
    }
  }

  public record Change(
      UUID eventId, String source, Instant serverTime, Snapshot snapshot, String checkpoint) {
    public Change(UUID eventId, String source, Instant serverTime, Snapshot snapshot) {
      this(eventId, source, serverTime, snapshot, serverTime + "|" + eventId);
    }

    public Change {
      if (eventId == null
          || eventId.equals(new UUID(0, 0))
          || source == null
          || source.isBlank()
          || serverTime == null
          || snapshot == null
          || checkpoint == null
          || checkpoint.isBlank()
          || checkpoint.length() > 2048)
        throw new IllegalArgumentException("Invalid customer event");
    }

    /** Stable length-prefixed digest; no transport JSON formatting or locale ambiguity. */
    public String digest() {
      try {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        for (String value :
            List.of(
                eventId.toString(),
                checkpoint,
                source,
                serverTime.toString(),
                snapshot.globalId().toString(),
                String.valueOf(snapshot.cloudId()),
                String.valueOf(snapshot.preferredBranch()),
                String.valueOf(snapshot.banned()),
                snapshot.banReason(),
                snapshot.banChangedAt().toString())) write(out, value);
        for (var e : new TreeMap<>(snapshot.fields()).entrySet()) {
          write(out, e.getKey());
          write(out, e.getValue());
        }
        for (var e : new TreeMap<>(snapshot.fieldTimes()).entrySet()) {
          write(out, e.getKey());
          write(out, e.getValue().toString());
        }
        return HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
      } catch (java.io.IOException | java.security.GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static void write(DataOutputStream stream, String value) throws java.io.IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    stream.writeInt(bytes.length);
    stream.write(bytes);
  }

  public record Match(String status, UUID target, List<UUID> candidates) {}

  public static Match match(List<Snapshot> local, Snapshot remote) {
    var phone = new ArrayList<Snapshot>();
    var email = new ArrayList<Snapshot>();
    for (var row : local) {
      if (row.fields().get("phone1").equals(remote.fields().get("phone1"))) phone.add(row);
      if (!remote.fields().get("email").isBlank()
          && row.fields().get("email").equalsIgnoreCase(remote.fields().get("email")))
        email.add(row);
    }
    var candidates = new HashSet<UUID>();
    phone.forEach(c -> candidates.add(c.globalId()));
    email.forEach(c -> candidates.add(c.globalId()));
    if (phone.size() > 1
        || email.size() > 1
        || !email.isEmpty()
            && (phone.isEmpty()
                || !email.getFirst().globalId().equals(phone.getFirst().globalId())))
      return new Match("CONFLICT", null, List.copyOf(candidates));
    if (phone.size() == 1)
      return new Match("LINK", phone.getFirst().globalId(), List.copyOf(candidates));
    return new Match("CREATE", null, List.of());
  }

  public static Snapshot merge(Snapshot local, Snapshot cloud) {
    var values = new HashMap<>(local.fields());
    var times = new HashMap<>(local.fieldTimes());
    for (String field :
        Set.of(
            "first_name", "last_name", "phone1", "phone2", "email", "preferred_store", "address")) {
      if (!cloud
          .fieldTimes()
          .get(field)
          .isBefore(local.fieldTimes().getOrDefault(field, Instant.EPOCH))) {
        if (field.equals("address"))
          for (String key : ADDRESS) values.put(key, cloud.fields().getOrDefault(key, ""));
        else values.put(field, cloud.fields().get(field));
        times.put(field, cloud.fieldTimes().get(field));
      }
    }
    boolean cloudBan = !cloud.banChangedAt().isBefore(local.banChangedAt());
    int preferred =
        !cloud
                .fieldTimes()
                .get("preferred_store")
                .isBefore(local.fieldTimes().get("preferred_store"))
            ? cloud.preferredBranch()
            : local.preferredBranch();
    return new Snapshot(
        cloud.globalId(),
        cloud.cloudId(),
        preferred,
        values,
        times,
        cloudBan ? cloud.banned() : local.banned(),
        cloudBan ? cloud.banReason() : local.banReason(),
        cloudBan ? cloud.banChangedAt() : local.banChangedAt());
  }

  public static void requireClock(Instant local, Instant remote) {
    if (Duration.between(local, remote).abs().compareTo(Duration.ofMinutes(2)) > 0)
      throw new IllegalStateException("CLOCK_SKEW");
  }

  public record Ack(
      UUID eventId, String contentDigest, String reference, UUID globalId, Long cloudId) {}

  public interface Transport {
    /**
     * Implementations must authenticate the remote service; this interface is never a JavaFX trust
     * flag.
     */
    String source();

    Instant serverTime() throws Exception;

    List<Change> changes(int preferredBranch, String checkpoint, int limit) throws Exception;

    Snapshot lookup(String normalizedPhone) throws Exception;

    Ack send(UUID eventId, String contentDigest, Map<String, Object> payload) throws Exception;
  }

  public interface Repository {
    String checkpoint();

    String receivedDigest(UUID eventId);

    void apply(Change change);

    void acknowledge(Ack acknowledgement);

    List<Map<String, Object>> pending(int limit);
  }

  /**
   * Not started by the application. The only current users are contract tests with an authenticated
   * fake transport.
   */
  public static final class Adapter {
    private final Transport transport;
    private final Repository repository;
    private final int branch;

    public Adapter(Transport transport, Repository repository, int branch) {
      Branch.fromId(branch);
      this.transport = transport;
      this.repository = repository;
      this.branch = branch;
    }

    public void exchange(Instant localNow) throws Exception {
      requireClock(localNow, transport.serverTime());
      for (var change : transport.changes(branch, repository.checkpoint(), 50)) {
        if (!change.source().equals(transport.source()))
          throw new SecurityException("Unexpected customer source");
        String known = repository.receivedDigest(change.eventId());
        if (known != null) {
          if (!known.equals(change.digest()))
            throw new IllegalStateException("Customer event content conflict");
          continue;
        }
        repository.apply(change);
      }
      for (var event : repository.pending(50)) {
        UUID id = UUID.fromString(event.get("eventId").toString());
        String digest = event.get("digest").toString();
        Ack ack = transport.send(id, digest, event);
        if (ack == null
            || !id.equals(ack.eventId())
            || !digest.equals(ack.contentDigest())
            || ack.reference() == null
            || ack.reference().isBlank())
          throw new IllegalStateException("Customer acknowledgement mismatch");
        repository.acknowledge(ack);
      }
    }

    public Snapshot lookup(String phone, Instant now) throws Exception {
      requireClock(now, transport.serverTime());
      return transport.lookup(Customers.phone(phone, true));
    }
  }
}

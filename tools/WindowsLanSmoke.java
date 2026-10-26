import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import za.co.onlinepos.installation.FileInstallationRepository;
import za.co.onlinepos.installation.LanPairingClient;
import za.co.onlinepos.installation.LanPolicy;
import za.co.onlinepos.installation.SecretStore;
import za.co.onlinepos.installation.TerminalRegistry;
import za.co.onlinepos.installation.WindowsSecretStore;

/** Isolated CI client. Exercises the installed service at its actual LAN address. */
public class WindowsLanSmoke {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(System.getenv("POS_DATA_ROOT"));
    var identity=new FileInstallationRepository(root).load().orElseThrow();
    var policy=LanPolicy.load(root);
    var registry=new TerminalRegistry(root,Clock.systemUTC(),identity,new WindowsSecretStore(root));
    Path temporary=Files.createTempDirectory("onlinepos-lan-test-");
    try {
      var disk=new WindowsSecretStore(temporary);
      var terminal=UUID.randomUUID();
      var failOnce=new SecretStore() {
        boolean fail=true;
        public byte[] get(String name) throws java.io.IOException {return disk.get(name);}
        public void put(String name,byte[] value) throws java.io.IOException {
          if(name.equals("terminal-p12") && fail){fail=false;throw new java.io.IOException("Simulated lost installation acknowledgement");}
          disk.put(name,value);
        }
      };
      try {LanPairingClient.connect(policy.address(),identity.branchId(),terminal,"LAN CI Slave",failOnce);throw new AssertionError("Expected interrupted credential write");}
      catch(java.io.IOException expected) { if(!"Simulated lost installation acknowledgement".equals(expected.getMessage()))throw expected; /* Master has already committed the registration. */ }
      byte[] pin=disk.get("pairing-trust");
      var connected=LanPairingClient.connect(policy.address(),identity.branchId(),terminal,"LAN CI Slave",disk);
      if(!connected.clusterId().equals(identity.clusterId()))throw new AssertionError("Wrong cluster");
      if(!java.util.Arrays.equals(pin,disk.get("pairing-trust")))throw new AssertionError("Retry replaced trust");
      if(registry.terminals().stream().filter(t->t.terminalId().equals(terminal)).count()!=1)throw new AssertionError("Duplicate terminal");
      var again=LanPairingClient.connect(policy.address(),identity.branchId(),terminal,"LAN CI Slave",new WindowsSecretStore(temporary));
      if(!again.equals(connected))throw new AssertionError("Restart changed identity");
      registry.revoke(terminal);
      try {LanPairingClient.connect(policy.address(),identity.branchId(),terminal,"LAN CI Slave",disk);throw new AssertionError("Revoked device joined");}
      catch(SecurityException expected) { /* Revocation cannot be undone through open enrolment. */ }
      System.out.println("WINDOWS_LAN_TOFU_ENROLMENT_RETRY_RESTART_REVOCATION_OK");
    } finally {
      try(var paths=Files.walk(temporary)){for(var file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
    }
  }
}

package za.co.onlinepos.installation;

import java.util.Map;

public interface DiagnosticsService {
  Map<String, String> inspect() throws Exception;
}

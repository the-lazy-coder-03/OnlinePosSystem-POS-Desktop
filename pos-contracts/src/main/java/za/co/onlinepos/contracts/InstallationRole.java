package za.co.onlinepos.contracts;

/** BACKUP_MASTER is a managed provisioning role, never an initial automatic election. */
public enum InstallationRole {
  MASTER,
  SLAVE,
  BACKUP_MASTER
}

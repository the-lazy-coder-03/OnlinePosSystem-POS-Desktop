# Desktop menu preview

From OnlinePosSystem-POS-Desktop run `./scripts/preview-desktop.sh` with JDK 25. The separate MenuPreview entry point opens the 3×3 grid. Click any module to open it without entering a PIN; Home / Lock returns to the grid.

The window and terminal name explicitly identify preview mode. Administration uses empty sample lists and catalogue uses the fictional integration fixture, read-only. Unimplemented modules retain their placeholder screens. This entry point never reads pairing credentials or connects to a live store. No fake credential is accepted by a real server. Normal Launcher startup opens Sales / Orders without a PIN or staff session. Other modules retain PIN validation, RBAC and session expiry. Opening Sales does not authorize protected backend operations; sales functions remain unimplemented.


Customers now opens the integrated SQL-field profile page from this repository in both preview and normal authenticated routing. It supports searching, creating and editing fictional customers in memory. The list displays Address, with no credit features; country, province and coordinates remain hidden in the form. Home / Lock returns to the grid and clears the page. Profiles reset when reopening the page or restarting; no customer database or cloud connection is implemented. Normal startup still requires staff PIN/module permission before showing the customer page.

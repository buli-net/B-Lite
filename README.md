# Bitcoin Wallet v58 – Android UI/resource cleanup

Based on v57.

This revision applies a standards-based Android cleanup without changing the wallet feature set:

- Replaced the legacy application logo and old logo assets with a new vector Bitcoin logo.
- Uses theme-derived system colors instead of hard-coded UI colors.
- Uses a dedicated white system-color notification icon for foreground-service notifications.
- Removed the internal AppCompat `MenuBuilder` icon-forcing hack; the app now uses the public XML menu/inflater path.
- Keeps Wallet Tools as a standard XML-defined submenu under the Toolbar menu.
- Moved static Address Book row presentation into XML.
- Moved static transaction-detail row and entry presentation into XML.
- Moved static dialog content for Address Book, recovery password, recovery phrase, wallet diagnostics, WIF information, and RBF fee input into XML.
- Reused the XML empty-state layout for dynamic empty lists.
- Removed the old logo PNG/vector resources and their references.
- Preserved dynamic UI generation only where the number of rows is data-driven at runtime (wallet selectors and send/watch lists).

The source was structurally checked and all XML resources parse successfully. Gradle compilation was attempted, but this environment could not download Gradle 5.6.4 because `services.gradle.org` was unreachable. Therefore this package is not claimed as locally Gradle-compiled.

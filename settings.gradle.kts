rootProject.name = "inventory-management-system"

include(
    "app",
    "inventory:core",
    "inventory:db",
    "inventory:schema",
    "support:flyway",
    "support:p6spy",
)

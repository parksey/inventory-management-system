rootProject.name = "inventory-management-system"

include(
    "app",
    "inventory:core",
    "inventory:db",
    "support:flyway",
    "support:p6spy",
)

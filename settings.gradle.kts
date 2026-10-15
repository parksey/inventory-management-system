rootProject.name = "inventory-management-system"

include(
    "app",
    "inventory-domain",
    "inventory-db",
    "support:flyway",
    "support:p6spy",
)

rootProject.name = "inventory-management-system"

include(
    "app:api",
    "inventory-domain",
    "inventory-db",
    "support:flyway",
)

rootProject.name = "inventory-management-system"

include(
    "app",
    "inventory:core",
    "inventory:db",
    "inventory:erp",
    "inventory:schema",
    "support:flyway",
    "support:p6spy",
)

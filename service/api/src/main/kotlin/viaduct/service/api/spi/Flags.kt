package viaduct.service.api.spi

import viaduct.apiannotations.ExperimentalApi

@ExperimentalApi
enum class Flags(override val flagName: String) : Flag {
    EXECUTE_ACCESS_CHECKS("execute_access_checks_in_modern_execution_strategy"),
    DISABLE_QUERY_PLAN_CACHE("disable_query_plan_cache"),
}

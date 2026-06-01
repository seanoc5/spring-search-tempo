@ApplicationModule(
        allowedDependencies = {
                "base::service",
                "base::model",
                "base::config",
                "base::domain",
                "base::util",
                "batch::ops"
        }
)
package com.oconeco.spring_search_tempo.web;

import org.springframework.modulith.ApplicationModule;

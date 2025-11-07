@ApplicationModule(
        allowedDependencies = {"base :: service", "base :: config", "base :: model", "base :: repos"}
)
package com.oconeco.spring_search_tempo.batch;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(
        type = ApplicationModule.Type.OPEN,
        displayName = "Base"
)
@org.springframework.modulith.NamedInterface(name = "config")
@org.springframework.modulith.NamedInterface(name = "service")
@org.springframework.modulith.NamedInterface(name = "repos")
@org.springframework.modulith.NamedInterface(name = "rest")
package com.oconeco.spring_search_tempo.base;

import org.springframework.modulith.ApplicationModule;
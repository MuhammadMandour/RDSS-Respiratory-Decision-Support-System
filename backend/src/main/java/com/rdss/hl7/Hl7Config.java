package com.rdss.hl7;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Hl7Config {

    /**
     * Messages of versions 2.3 to 2.5.1 are parsed into the v2.5 structures, so handlers work
     * with one model. HAPI's default validation (data types, field lengths) is applied.
     */
    @Bean(destroyMethod = "close")
    HapiContext hapiContext() {
        HapiContext ctx = new DefaultHapiContext();
        ctx.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
        ctx.setValidationContext(ValidationContextFactory.defaultValidation());
        return ctx;
    }
}

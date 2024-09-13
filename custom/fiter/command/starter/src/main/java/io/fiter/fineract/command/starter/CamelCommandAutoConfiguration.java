package io.fiter.fineract.command.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;

@AutoConfiguration
@ComponentScans({ @ComponentScan("io.fiter.fineract.command.service") })
public class CamelCommandAutoConfiguration {}

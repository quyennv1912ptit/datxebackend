package com.nhom15.datxebackend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class DatxebackendApplication

fun main(args: Array<String>) {
	runApplication<DatxebackendApplication>(*args)
}

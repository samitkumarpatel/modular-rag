package net.samitkumar.modular_rag;

import org.springframework.boot.SpringApplication;

public class TestModularRagApplication {

	public static void main(String[] args) {
		SpringApplication.from(ModularRagApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

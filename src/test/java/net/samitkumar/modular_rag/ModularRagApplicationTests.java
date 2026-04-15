package net.samitkumar.modular_rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ModularRagApplicationTests {

	@Test
	void contextLoads() {
	}

}

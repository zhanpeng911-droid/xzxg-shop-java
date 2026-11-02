package com.xzxg.shop.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Exercises the image bootstrap SQL against a real MySQL schema. */
@SpringBootTest
@ActiveProfiles("mysql-it")
class VectorBootstrapIT {

    @Autowired
    private VectorBootstrapService bootstrap;

    @Test
    void imageTasksQueryIsAmbiguityFreeAndReturnsSeededProducts() {
        List<VectorBootstrapService.ImageTask> tasks = bootstrap.imageTasks();

        assertThat(tasks).isNotEmpty();
        assertThat(tasks).allSatisfy(task -> {
            assertThat(task.vectorId()).startsWith("img_");
            assertThat(task.productId()).isNotBlank();
            assertThat(task.merchantId()).isNotBlank();
            assertThat(task.source()).isNotBlank();
            assertThat(task.imageType()).isIn("main", "detail");
        });
        assertThat(tasks.stream().map(VectorBootstrapService.ImageTask::productId))
                .contains("p_001");
    }
}

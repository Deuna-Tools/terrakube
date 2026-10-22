package io.terrakube.executor.service.terraform;

import com.sun.net.httpserver.HttpServer;
import io.terrakube.terraform.TerraformDownloader;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerraformReleaseAvailabilityTest {

    @Test
    void terraformLookupIgnoresUnavailableTofuAndReusesReleaseCache() throws Exception {
        AtomicInteger terraformRequests = new AtomicInteger();
        AtomicInteger tofuRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/terraform", exchange -> {
            terraformRequests.incrementAndGet();
            byte[] body = """
                    {"name":"terraform","versions":{"1.5.7":{"version":"1.5.7","builds":[]}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/tofu", exchange -> {
            tofuRequests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            TerraformDownloader first = new TerraformDownloader(base + "/terraform", base + "/tofu");
            assertEquals("1.5.7", first.resolveTerraformVersion("1.5.7"));
            TerraformDownloader second = new TerraformDownloader(base + "/terraform", base + "/tofu");
            assertEquals("1.5.7", second.resolveTerraformVersion("1.5.7"));
            assertEquals(1, terraformRequests.get());
            assertEquals(0, tofuRequests.get());
        } finally {
            server.stop(0);
        }
    }
}

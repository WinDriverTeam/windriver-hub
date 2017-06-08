package com.epam.windriver.hub;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Controller
@EnableAutoConfiguration
public class FrontController {

    private static final Logger LOG = Logger.getLogger(FrontController.class);

    // key - sessionId, value - nodeUrl
    private final ConcurrentHashMap<String, String> availableNodes = new ConcurrentHashMap<>();

    @Autowired
    private RestTemplate restTemplate;

    private final ConcurrentHashMap<String, String> getAvailableNodes() {
        // Remove non available nodes
        for (String sessionId : availableNodes.keySet()) {
            String nodeUrl = availableNodes.get(sessionId);

            try {
                ResponseEntity<String> healthStatusModel = restTemplate.getForEntity(nodeUrl + "/WinAuto/HealthCheck",
                        String.class);

                if (!healthStatusModel.getBody().contains("OK")) {
                    throw new HttpClientErrorException(HttpStatus.OK,
                            String.format("Response from node [%s] is not valid", nodeUrl));
                }
            } catch (Exception ex) {
                LOG.error(String.format("Node [%s] already unavailable", nodeUrl));
                availableNodes.remove(sessionId);
            }
        }
        LOG.info("Nodes list: " + availableNodes);
        return availableNodes;
    }

    @RequestMapping("/register")
    public ResponseEntity<Map> registerNode(@RequestParam("nodeUrl") String nodeUrl) {
        LOG.info(String.format("Registering node from URL=[%s]", nodeUrl));
        Map<String, Object> responseBody = new HashMap<>();
        String sessionId = UUID.randomUUID().toString();
        availableNodes.put(sessionId, nodeUrl);
        responseBody.put("sessionId", sessionId);
        responseBody.put("message", "Node from [" + nodeUrl + "] has been registered");
        responseBody.put("AllAvailableNodes", getAvailableNodes());
        return new ResponseEntity<>(responseBody, HttpStatus.OK);
    }

    @RequestMapping("/nodes")
    public ResponseEntity<Map> getAllNodes() {
        LOG.info("Get all nodes ... ");
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("AllAvailableNodes", getAvailableNodes());
        return new ResponseEntity<>(responseBody, HttpStatus.OK);
    }

    @RequestMapping("/**")
    @ResponseBody
    public ResponseEntity<String> intercept(HttpServletRequest request, HttpServletResponse response)
            throws IOException, URISyntaxException {

        final StringBuilder body = new StringBuilder();

        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        } catch (Exception e) {
            throw e;
        }

        Map<String, String> headerMap = Collections.list(request.getHeaderNames()).stream()
                .collect(Collectors.toMap(h -> h, request::getHeader));

        MultiValueMap<String, String> headers = new HttpHeaders();

        headers.setAll(headerMap);

        LOG.info(String.format("New request has been intercepted. Details: HttpMethod=[%s], headers=[%s], body=[%s]",
                HttpMethod.resolve(request.getMethod()), headers, body.toString()));

        RequestEntity<String> requestEntity = new RequestEntity<>(body.toString(), headers,
                HttpMethod.resolve(request.getMethod()), new URI(getRequestURI(request)));

        ResponseEntity<String> response2 = restTemplate.exchange(requestEntity, String.class);
        LOG.info("###response2: "+response2);
        return  response2;
    }

    private String getRequestURI(HttpServletRequest request) {
        String sessionId = request.getHeader("windriver_sessionId");
        return availableNodes.get(sessionId) + request.getRequestURI();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    public static void main(String[] args) throws Exception {
        SpringApplication.run(FrontController.class, args);
    }
}

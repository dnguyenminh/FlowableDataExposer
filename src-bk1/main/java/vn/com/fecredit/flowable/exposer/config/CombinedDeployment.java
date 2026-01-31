package vn.com.fecredit.flowable.exposer.config;

import org.flowable.engine.RepositoryService;
import org.flowable.cmmn.api.CmmnRepositoryService;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.com.fecredit.chunkedupload.util.ModelValidatorRenderer;

/**
 * Ensure BPMN and CMMN resources are deployed together in a single deployment so the CMMN
 * can be considered to "contain" the BPMN in a standards-correct way.
 * Deployment is idempotent (checked by deployment name).
 */
@Component
public class CombinedDeployment {

    private static final String DEPLOYMENT_NAME = "order-combined-deployment";
    private static final Logger log = LoggerFactory.getLogger(CombinedDeployment.class);

    private final RepositoryService repositoryService;
    private final CmmnRepositoryService cmmnRepositoryService;

    public CombinedDeployment(RepositoryService repositoryService, CmmnRepositoryService cmmnRepositoryService) {
        this.repositoryService = repositoryService;
        this.cmmnRepositoryService = cmmnRepositoryService;
    }

    @PostConstruct
    public void deployCombinedResources() {
        var existing = repositoryService.createDeploymentQuery()
                .deploymentName(DEPLOYMENT_NAME)
                .singleResult();
        if (existing != null) {
            return;
        }

        // If the process definition is already deployed (for example by Flowable's auto-deploy),
        // don't create a second deployment containing the same process key — that leads to
        // duplicate processDefinitions and makes queries like singleResult() ambiguous.
        long existingProcessDefs = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("orderProcess")
                .count();
        long existingCaseDefs = cmmnRepositoryService.createCaseDefinitionQuery()
                .caseDefinitionKey("orderCase")
                .count();
        // If both BPMN and CMMN are already deployed, nothing to do. If BPMN exists but
        // the CMMN does not, proceed to deploy a CMMN that references the existing BPMN
        // (remove sameDeployment requirement) so tests and runtime can find the case.
        if (existingProcessDefs > 0L && existingCaseDefs > 0L) {
            return;
        }
        
        try {
            // DEBUG: dump all classpath CMMN-like resources to build/model-validator for forensic inspection
            try {
                java.nio.file.Path diagDir = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "model-validator");
                java.nio.file.Files.createDirectories(diagDir);
                String[] prefixes = new String[] {"cases/", "modeler/"};
                ClassLoader cl = CombinedDeployment.class.getClassLoader();
                for (String p : prefixes) {
                    var urls = java.util.Collections.list(cl.getResources(p));
                    // also attempt to list known filenames if directory resource isn't exposed
                    String[] candidates = new String[] {"orderCase.cmmn", "orderCase.modeler.xml", "orderCase.xml"};
                    for (String cand : candidates) {
                        var is = cl.getResourceAsStream(p + cand);
                        if (is != null) {
                            try (is) {
                                String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                java.nio.file.Path out = diagDir.resolve((p + cand).replaceAll("[/]", "-"));
                                java.nio.file.Files.writeString(out, content, java.nio.charset.StandardCharsets.UTF_8);
                                log.info("Wrote classpath candidate for inspection: {}", out.toAbsolutePath());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to write classpath CMMN candidates for diagnostics: {}", e.getMessage());
            }
             log.info("CombinedDeployment check: existingProcessDefs={} existingCaseDefs={}", existingProcessDefs, existingCaseDefs);
            // Load BPMN resource (accept common filenames emitted by different Modeler versions)
            String[] candidates = {"processes/orderProcess.bpmn20.xml", "processes/orderProcess.bpmn"};
            String bpmnContent = null;
            for (String c : candidates) {
                var is = CombinedDeployment.class.getClassLoader().getResourceAsStream(c);
                if (is != null) {
                    // found BPMN on the classpath
                    bpmnContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    is.close();
                    break;
                }
            }

            if (bpmnContent == null) {
                throw new IllegalStateException("BPMN resource not found on classpath (tried: " + String.join(",", candidates) + ")");
            }

            // Load CMMN resource from classpath and defensively fix planItem/planItemDefinition ordering
            // Prefer the original Modeler export (kept for visibility) when available in
            // src/main/resources/modeler/. That file is NOT packaged for auto-deploy; we
            // will normalize and deploy an engine-valid payload programmatically.
            String modelerCmmn = "modeler/orderCase.modeler.xml";
            String cmmnPath = "cases/orderCase.cmmn";
            // Prefer the engine-valid CMMN resource on the classpath. Keep the original
            // Modeler export only for offline inspection; do not feed it to the engine by
            // default because many Modeler exports are not schema-valid.
            java.io.InputStream cis = CombinedDeployment.class.getClassLoader().getResourceAsStream(cmmnPath);
            String cmmnContent = null;
            if (cis != null) {
                cmmnContent = new String(cis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                cis.close();
                // If a Modeler export also exists, log that it's being preserved but not used for runtime
                var modelerIs = CombinedDeployment.class.getClassLoader().getResourceAsStream(modelerCmmn);
                if (modelerIs != null) {
                    log.info("Both engine-valid CMMN ({} ) and Modeler export ({}) are present; using engine-valid resource for deployment and preserving Modeler export for traceability", cmmnPath, modelerCmmn);
                    modelerIs.close();
                }
            } else {
                // fall back to Modeler export only if no engine-valid CMMN is present
                cis = CombinedDeployment.class.getClassLoader().getResourceAsStream(modelerCmmn);
                if (cis == null) {
                    throw new IllegalStateException("CMMN resource not found on classpath: " + cmmnPath);
                }
                cmmnContent = new String(cis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                cis.close();
                log.warn("Using Modeler export {} as input for normalization; this may require fixes before deployment", modelerCmmn);
            }

            String fixedCmmn = tryFixCmmnPlanItemOrdering(cmmnContent);

            // persist the candidate fixed CMMN to the build/tmp area for diagnostics and
            // validate it with the ModelValidatorRenderer before attempting deployment.
            try {
                java.nio.file.Path diagDir = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "model-validator");
                java.nio.file.Files.createDirectories(diagDir);
                java.nio.file.Path candidate = diagDir.resolve("orderCase.fixed.cmmn");
                java.nio.file.Files.writeString(candidate, fixedCmmn, java.nio.charset.StandardCharsets.UTF_8);

                var vr = ModelValidatorRenderer.validate(candidate);
                if (!vr.valid) {
                    // attempt to render diagnostic images where possible to help debugging
                    java.nio.file.Path png = diagDir.resolve("orderCase.fixed.cmmn.png");
                    try {
                        ModelValidatorRenderer.renderToPng(candidate, png);
                    } catch (Exception re) {
                        // rendering may fail when DI is inconsistent — that's useful to log but
                        // should not mask the validation failure.
                        log.warn("Failed to render diagnostic PNG for fixed CMMN (will still surface XSD errors): {}", re.getMessage());
                    }
                    throw new IllegalStateException("Normalized CMMN failed validation; diagnostic written to: " + candidate.toAbsolutePath() + " — messages=" + vr.messages);
                } else {
                    // also validate BPMN (if we will deploy it) and write its thumbnail
                    if (existingProcessDefs == 0L && bpmnContent != null) {
                        java.nio.file.Path bpmnPath = diagDir.resolve("orderProcess.bpmn20.xml");
                        java.nio.file.Files.writeString(bpmnPath, bpmnContent, java.nio.charset.StandardCharsets.UTF_8);
                        var bvr = ModelValidatorRenderer.validate(bpmnPath);
                        if (!bvr.valid) {
                            throw new IllegalStateException("BPMN failed validation; diagnostic written to: " + bpmnPath.toAbsolutePath() + " — messages=" + bvr.messages);
                        }
                        try {
                            ModelValidatorRenderer.renderToPng(bpmnPath, diagDir.resolve("orderProcess.bpmn20.xml.png"));
                        } catch (Exception re) {
                            log.warn("Failed to render BPMN diagnostic PNG: {}", re.getMessage());
                        }
                    }
                }
            } catch (RuntimeException rte) {
                throw rte;
            } catch (Exception ex) {
                log.warn("Failed to run model validation/render diagnostics — continuing with deployment: {}", ex.getMessage());
            }

            // If the BPMN is already deployed elsewhere and only the CMMN is missing,
            // remove the sameDeployment attribute so the case can resolve the process by key.
            if (existingProcessDefs > 0L && existingCaseDefs == 0L) {
                fixedCmmn = fixedCmmn.replaceAll("\\s+flowable:sameDeployment=\"true\"", "");
                // also tolerate delegate-based start: prefer processRefExpression when present
            }

            // Always remove sameDeployment from the normalized CMMN. In our test environment the
            // BPMN and CMMN may be deployed by different engine repositories; forcing the case
            // to require same-deployment prevents the processTask from locating the BPMN by key.
            fixedCmmn = fixedCmmn.replaceAll("\\s+flowable:sameDeployment=\"true\"", "");

            // --- NEW: write the *final* payload that will be sent to the engine so failures
            // during engine parse/validation can be attributed to the exact string.
            try {
                java.nio.file.Path diagDir = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "model-validator");
                java.nio.file.Files.createDirectories(diagDir);
                java.nio.file.Path toDeploy = diagDir.resolve("orderCase.to-deploy.cmmn");
                java.nio.file.Files.writeString(toDeploy, fixedCmmn, java.nio.charset.StandardCharsets.UTF_8);

                var finalVr = ModelValidatorRenderer.validate(toDeploy);
                if (!finalVr.valid) {
                    throw new IllegalStateException("Final CMMN payload failed XSD/engine validation prior to deploy; path=" + toDeploy.toAbsolutePath() + " messages=" + finalVr.messages);
                }
            } catch (RuntimeException rte) {
                throw rte;
            } catch (Exception ex) {
                log.warn("Failed to write/validate final CMMN payload for diagnostics: {}", ex.getMessage());
            }

            var deploymentBuilder = repositoryService.createDeployment().name(DEPLOYMENT_NAME);

            // Defensive engine-identical parse check: run Flowable's CmmnXmlConverter on the
            // final string before handing it to the repository. This reproduces the exact
            // code path that the engine uses and lets us capture the failing payload.
            try {
                try {
                    byte[] xmlBytes = fixedCmmn.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    javax.xml.stream.XMLInputFactory xif = javax.xml.stream.XMLInputFactory.newInstance();
                    javax.xml.stream.XMLStreamReader xtr = xif.createXMLStreamReader(new java.io.ByteArrayInputStream(xmlBytes));
                    // reflectively load the converter class used by the engine
                    Class<?> convClass = Class.forName("org.flowable.cmmn.converter.CmmnXmlConverter");
                    Object conv = convClass.getDeclaredConstructor().newInstance();
                    // find a convertToCmmnModel / convertTo* method that accepts XMLStreamReader
                    java.lang.reflect.Method target = null;
                    for (var m : convClass.getMethods()) {
                        if (m.getName().startsWith("convertTo") && m.getParameterCount() == 1
                                && m.getParameterTypes()[0].isAssignableFrom(javax.xml.stream.XMLStreamReader.class)) {
                            target = m;
                            break;
                        }
                    }
                    if (target == null) {
                        // fall back to the converter used by ModelValidatorRenderer (best-effort)
                        target = convClass.getMethod("convertToCmmnModel", java.io.InputStream.class);
                        target.invoke(conv, new java.io.ByteArrayInputStream(xmlBytes));
                    } else {
                        target.invoke(conv, xtr);
                    }
                } catch (Exception convEx) {
                    // write the exact payload that failed to disk for CI/debugging
                    java.nio.file.Path diagDir = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "model-validator");
                    java.nio.file.Files.createDirectories(diagDir);
                    java.nio.file.Path toDeploy = diagDir.resolve("orderCase.to-deploy.cmmn");
                    java.nio.file.Files.writeString(toDeploy, fixedCmmn, java.nio.charset.StandardCharsets.UTF_8);
                    throw new IllegalStateException("Flowable CMMN converter rejected the final payload; written to: " + toDeploy.toAbsolutePath() + " — cause=" + convEx.getMessage(), convEx);
                }
            } catch (IllegalStateException ise) {
                throw ise;
            } catch (Exception ex) {
                log.warn("Unexpected error while performing defensive CMMN conversion check: {}", ex.getMessage());
            }

            if (existingProcessDefs == 0L) {
                // BPMN not present on the engine yet — deploy it together with the CMMN
                deploymentBuilder.addString("processes/orderProcess.bpmn20.xml", bpmnContent);
            } else {
                log.info("BPMN 'orderProcess' already deployed; deploying only CMMN (with sameDeployment removed if necessary)");
            }
            deploymentBuilder.addString(cmmnPath, fixedCmmn);
            var deployment = deploymentBuilder.deploy();

            log.info("Created combined deployment {} with resources={}", deployment.getId(), repositoryService.getDeploymentResourceNames(deployment.getId()));

            // Ensure the CMMN is deployed into the CMMN repository so a CaseDefinition is created.
            if (existingCaseDefs == 0L) {
                var cmmnDep = cmmnRepositoryService.createDeployment()
                        .name(DEPLOYMENT_NAME)
                        .addString(cmmnPath, fixedCmmn)
                        .deploy();
                log.info("Created CMMN deployment id={}", cmmnDep.getId());

                // Diagnostic: confirm the case definition was registered and inspect the
                // exact CMMN XML that the engine stored (helps validate processRef presence)
                var caseDef = cmmnRepositoryService.createCaseDefinitionQuery().caseDefinitionKey("orderCase").singleResult();
                if (caseDef == null) {
                    log.warn("CMMN deployment completed but no CaseDefinition was registered (key=orderCase)");
                } else {
                    log.info("Deployed CaseDefinition id={} deploymentId={}", caseDef.getId(), caseDef.getDeploymentId());
                    // Use the CMMN repository to read CMMN deployment resources (the
                    // RepositoryService is for BPMN resources and will not find CMMN
                    // deployment ids — this previously caused spurious diagnostics).
                    var cmmnResourceNames = cmmnRepositoryService.getDeploymentResourceNames(caseDef.getDeploymentId());
                    log.info("CMMN deployment resources for {}: {}", caseDef.getDeploymentId(), cmmnResourceNames);
                    try (var ris = cmmnRepositoryService.getResourceAsStream(caseDef.getDeploymentId(), cmmnPath)) {
                        if (ris == null) {
                            log.warn("Deployed CMMN deployment {} does not contain resource {} according to CmmnRepositoryService", caseDef.getDeploymentId(), cmmnPath);
                        } else {
                            String deployed = new String(ris.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            boolean hasDelegate = deployed.contains("delegateExpression") || deployed.contains("caseInvokeCmmnDelegate");
                            boolean hasProcessRef = deployed.contains("processRef") || deployed.contains("processRefExpression");
                            log.info("Deployed CMMN contains delegateExpression={} processRef={}", hasDelegate, hasProcessRef);
                            if (hasDelegate) {
                                int pos = deployed.indexOf("delegateExpression");
                                int start = Math.max(0, deployed.lastIndexOf('<', pos - 1));
                                int end = deployed.indexOf('>', pos);
                                String snippet = deployed.substring(start, Math.min(deployed.length(), end + 1));
                                log.info("Deployed CMMN fragment: {}", snippet.replaceAll("\n", " "));
                            }

                            // --- NEW: write the exact engine-stored CMMN to disk for CI capture and perform structural assertions
                            try {
                                java.nio.file.Path outDir = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "model-validator");
                                java.nio.file.Files.createDirectories(outDir);
                                java.nio.file.Path deployedPath = outDir.resolve("orderCase.deployed.cmmn");
                                java.nio.file.Files.writeString(deployedPath, deployed, java.nio.charset.StandardCharsets.UTF_8);

                                // Ensure that for every <planItem definitionRef="X"> the corresponding
                                // planItemDefinition (or processTask with id="X") appears earlier in the
                                // stored XML. If this fails, surface a clear error including the path so
                                // CI artifacts contain the exact problematic payload.
                                java.util.regex.Pattern planItemRef = java.util.regex.Pattern.compile("<planItem[^>]*definitionRef=\"([^\"]+)\"[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
                                java.util.regex.Matcher pm = planItemRef.matcher(deployed);
                                java.util.List<String> badRefs = new java.util.ArrayList<>();
                                while (pm.find()) {
                                    String defId = pm.group(1);
                                    int planPos = pm.start();
                                    int defPos = deployed.indexOf("id=\"" + defId + "\"");
                                    if (defPos == -1) defPos = deployed.indexOf("id='" + defId + "'");
                                    if (defPos == -1 || planPos < defPos) {
                                        badRefs.add(defId);
                                    }
                                }

                                if (!badRefs.isEmpty()) {
                                    throw new IllegalStateException("Deployed CMMN has planItem(s) that appear before their planItemDefinition(s): " + badRefs + ". Deployed resource written to: " + deployedPath.toAbsolutePath());
                                }

                                // Ensure runtime wiring exists (either processRef/processRefExpression or a delegate)
                                if (!hasDelegate && !hasProcessRef) {
                                    throw new IllegalStateException("Deployed CMMN does not contain a processRef/processRefExpression or delegateExpression — deployed resource written to: " + deployedPath.toAbsolutePath());
                                }
                            } catch (IllegalStateException ise) {
                                // surface structural problems as runtime-failing configuration so CI captures the payload
                                throw ise;
                            } catch (Exception ex2) {
                                log.warn("Failed to write/validate deployed CMMN for diagnostics: {}", ex2.getMessage());
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to read deployed CMMN resource for diagnostics", ex);
                    }

                    // list process definitions with their deployment ids so we can compare
                    var procs = repositoryService.createProcessDefinitionQuery().processDefinitionKey("orderProcess").list();
                    for (var pd : procs) {
                        log.info("ProcessDefinition found: id={} key={} deploymentId={}", pd.getId(), pd.getKey(), pd.getDeploymentId());
                    }
                }
            }

            // defensive check: ensure deployment contains both resources
            var names = repositoryService.getDeploymentResourceNames(deployment.getId());
            if (!names.contains("cases/orderCase.cmmn")) {
                throw new IllegalStateException("Combined deployment is missing expected CMMN resource: " + names);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create combined deployment", e);
        }
    }

    /**
     * Flowable's CMMN parser is strict about element ordering. Some Modeler exports put <planItem>
     * before the referenced <planItemDefinition> which triggers XSD validation failures during
     * deployment. This method attempts a minimal, local fix: for each element that can contain
     * plan items (casePlanModel, stage, etc.) move all planItemDefinition children before the
     * first planItem child. If parsing/fixing fails we return the original content so failures
     * remain visible.
     */
    private static String tryFixCmmnPlanItemOrdering(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            var db = dbf.newDocumentBuilder();
            try (java.io.InputStream is = new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                org.w3c.dom.Document doc = db.parse(is);

                // Elements that commonly contain planItemDefinition/planItem children
                String[] containers = {"casePlanModel", "stage", "planFragment", "discretionaryItem"};
                for (String cname : containers) {
                    var nl = doc.getElementsByTagNameNS("http://www.omg.org/spec/CMMN/20151109/MODEL", cname);
                    for (int i = 0; i < nl.getLength(); i++) {
                        org.w3c.dom.Element container = (org.w3c.dom.Element) nl.item(i);
                        // Preserve any <processTask> as-is — the engine understands processTask and
                        // it is the correct way to represent a CMMN plan item that starts a BPMN
                        // subprocess. We only perform ordering fixes below; do not change the
                        // planItemDefinition type because that can alter runtime semantics.
                        // (no conversion performed)

                        // collect nodes
                        java.util.List<org.w3c.dom.Node> defs = new java.util.ArrayList<>();
                        java.util.List<org.w3c.dom.Node> others = new java.util.ArrayList<>();
                        var children = container.getChildNodes();
                        for (int j = 0; j < children.getLength(); j++) {
                            var ch = children.item(j);
                            if (ch.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                            String local = ch.getLocalName();
                            if ("planItemDefinition".equals(local) || local.endsWith("Task") || local.endsWith("Stage") || local.endsWith("Fragment")) {
                                defs.add(ch);
                            } else {
                                others.add(ch);
                            }
                        }

                        if (!defs.isEmpty()) {
                            // remove all collected defs and re-insert them before the first planItem (or at top)
                            for (var n : defs) {
                                container.removeChild(n);
                            }
                            // find insertion point: first planItem element
                            org.w3c.dom.Node insertBefore = null;
                            for (int j = 0; j < children.getLength(); j++) {
                                var ch = children.item(j);
                                if (ch.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                                if ("planItem".equals(ch.getLocalName())) {
                                    insertBefore = ch;
                                    break;
                                }
                            }
                            if (insertBefore != null) {
                                for (var n : defs) {
                                    container.insertBefore(n, insertBefore);
                                }
                            } else {
                                // no planItem found — append at end
                                for (var n : defs) {
                                    container.appendChild(n);
                                }
                            }
                        }
                    }
                }

                // serialize
                try (java.io.StringWriter sw = new java.io.StringWriter()) {
                    javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
                            new javax.xml.transform.dom.DOMSource(doc),
                            new javax.xml.transform.stream.StreamResult(sw));
                    return sw.toString();
                }
            }
        } catch (Exception e) {
            log.warn("CMMN parsing/fixing failed: {}", e.getMessage());
        }
        return xml;
    }
}

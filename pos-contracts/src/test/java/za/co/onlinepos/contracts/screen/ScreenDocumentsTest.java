package za.co.onlinepos.contracts.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.ItemCustomization;

class ScreenDocumentsTest {
  ItemCustomization.Catalog catalog() {
    return new ItemCustomization.Catalog(1, Map.of(), Map.of(), Map.of(), null, false);
  }

  @Test
  void starterCopiesHaveIndependentIdentitiesAndValidate() {
    var store = UUID.randomUUID();
    var doc = ScreenDocuments.starter(store, catalog());
    ScreenValidation.validate(doc, store, Set.of(), ScreenValidation.Limits.standard());
    var copy = ScreenDocuments.copy(doc, store);
    assertNotEquals(doc.screenId(), copy.screenId());
    assertNotEquals(doc.pages().getFirst().id(), copy.pages().getFirst().id());
    ScreenValidation.validate(copy, store, Set.of(), ScreenValidation.Limits.standard());
  }

  @Test
  void rejectsWrongStoreInvalidGeometryAndCyclicWorkflows() {
    var store = UUID.randomUUID();
    var d = ScreenDocuments.starter(store, catalog());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScreenValidation.validate(
                d, UUID.randomUUID(), Set.of(), ScreenValidation.Limits.standard()));
    var w = d.pages().getFirst().widgets().getFirst();
    var page =
        new SalesScreens.Page(
            d.pages().getFirst().id(),
            "Invalid",
            SalesScreens.Purpose.SALES,
            List.of(
                ScreenDocuments.geometry(w, new SalesScreens.Rect(Double.NaN, 0, 100, 100, 1))));
    var invalid = ScreenDocuments.page(d, page);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScreenValidation.validate(
                invalid, store, Set.of(), ScreenValidation.Limits.standard()));
    var id = UUID.randomUUID();
    var flow =
        new SalesScreens.Workflow(
            UUID.randomUUID(),
            "Cycle",
            id,
            List.of(
                new SalesScreens.Step(
                    id,
                    SalesScreens.NodeType.ACTION,
                    SalesScreens.Action.SAVE_DRAFT,
                    null,
                    null,
                    id,
                    null)));
    var cyclic =
        new SalesScreens.Document(
            1, d.screenId(), store, d.canvas(), d.pages(), List.of(flow), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ScreenValidation.validate(cyclic, store, Set.of(), ScreenValidation.Limits.standard()));
  }

  @Test
  void templateConflictKeepsLocalUntilSelected() {
    var store = UUID.randomUUID();
    var base = ScreenDocuments.starter(store, catalog());
    var template = new SalesScreens.Revision(base.screenId(), 1, "Template", true, "", base);
    var target = ScreenDocuments.applyTemplate(ScreenDocuments.starter(store, catalog()), template);
    var origin = target.templateOrigins().getFirst();
    var source = base.pages().getFirst().widgets().getFirst();
    var localPage =
        target.pages().stream()
            .filter(p -> p.id().equals(origin.pageId()))
            .findFirst()
            .orElseThrow();
    var local = localPage.widgets().getFirst();
    var changed =
        new SalesScreens.Widget(
            local.id(),
            local.type(),
            local.rect(),
            local.style(),
            "Local",
            local.binding(),
            local.options(),
            local.access(),
            local.groupId());
    target =
        ScreenDocuments.page(
            target,
            new SalesScreens.Page(
                localPage.id(),
                localPage.name(),
                localPage.purpose(),
                localPage.widgets().stream()
                    .map(w -> w.id().equals(local.id()) ? changed : w)
                    .toList()));
    var remote =
        new SalesScreens.Widget(
            source.id(),
            source.type(),
            source.rect(),
            source.style(),
            "Incoming",
            source.binding(),
            source.options(),
            source.access(),
            source.groupId());
    var next =
        ScreenDocuments.page(
            base,
            new SalesScreens.Page(
                base.pages().getFirst().id(),
                "Products",
                SalesScreens.Purpose.SALES,
                base.pages().getFirst().widgets().stream()
                    .map(w -> w.id().equals(source.id()) ? remote : w)
                    .toList()));
    var revision = new SalesScreens.Revision(base.screenId(), 2, "Template", true, "", next);
    var review = ScreenDocuments.updateTemplate(target, origin, revision, Set.of());
    assertEquals(1, review.conflicts().stream().filter(c -> c.field().equals("label")).count());
    var accepted =
        ScreenDocuments.updateTemplate(target, origin, revision, Set.of(local.id() + ".label"));
    assertEquals(
        "Incoming",
        accepted.document().pages().stream()
            .filter(p -> p.id().equals(origin.pageId()))
            .findFirst()
            .orElseThrow()
            .widgets()
            .getFirst()
            .label());
  }

  @Test
  void selectedTemplateWorkflowUpdatesDoNotChangeOtherCopies() {
    UUID store = UUID.randomUUID(),
        prompt = UUID.randomUUID(),
        action = UUID.randomUUID(),
        end = UUID.randomUUID(),
        flowId = UUID.randomUUID();
    var workflow =
        new SalesScreens.Workflow(
            flowId,
            "Save with confirmation",
            prompt,
            List.of(
                new SalesScreens.Step(
                    prompt,
                    SalesScreens.NodeType.PROMPT,
                    null,
                    null,
                    "Save this draft?",
                    action,
                    null),
                new SalesScreens.Step(
                    action,
                    SalesScreens.NodeType.ACTION,
                    SalesScreens.Action.SAVE_DRAFT,
                    null,
                    null,
                    end,
                    null),
                new SalesScreens.Step(
                    end, SalesScreens.NodeType.END, null, null, null, null, null)));
    var button =
        ScreenDocuments.widget(
            SalesScreens.Type.FUNCTION_BUTTON,
            "Save",
            new SalesScreens.Rect(20, 20, 200, 60, 1),
            Map.of("workflowId", flowId.toString()));
    var templateDoc =
        new SalesScreens.Document(
            1,
            UUID.randomUUID(),
            store,
            new SalesScreens.Canvas(1200, 720, 8),
            List.of(
                new SalesScreens.Page(
                    UUID.randomUUID(), "Template", SalesScreens.Purpose.SALES, List.of(button))),
            List.of(workflow),
            List.of());
    var template =
        new SalesScreens.Revision(templateDoc.screenId(), 1, "Template", true, "", templateDoc);
    var target = ScreenDocuments.starter(store, catalog());
    for (int i = 0; i < 5; i++) target = ScreenDocuments.applyTemplate(target, template);
    var origins = List.copyOf(target.templateOrigins());
    var revisedFlow =
        new SalesScreens.Workflow(
            flowId,
            workflow.name(),
            prompt,
            workflow.steps().stream()
                .map(
                    n ->
                        n.id().equals(prompt)
                            ? new SalesScreens.Step(
                                prompt,
                                SalesScreens.NodeType.PROMPT,
                                null,
                                null,
                                "Ready to save the draft?",
                                action,
                                null)
                            : n)
                .toList());
    var revised =
        new SalesScreens.Document(
            1,
            templateDoc.screenId(),
            store,
            templateDoc.canvas(),
            templateDoc.pages(),
            List.of(revisedFlow),
            List.of());
    var revision =
        new SalesScreens.Revision(templateDoc.screenId(), 2, "Template", true, "", revised);
    for (int i = 0; i < 2; i++) {
      var origin = origins.get(i);
      UUID copiedFlow = origin.referenceIds().get(flowId);
      var review = ScreenDocuments.updateTemplate(target, origin, revision, Set.of());
      assertEquals(
          1, review.conflicts().stream().filter(c -> c.field().equals("workflow")).count());
      target =
          ScreenDocuments.updateTemplate(target, origin, revision, Set.of(copiedFlow + ".workflow"))
              .document();
    }
    for (int i = 0; i < origins.size(); i++) {
      UUID pageId = origins.get(i).pageId();
      var copiedPage =
          target.pages().stream().filter(p -> p.id().equals(pageId)).findFirst().orElseThrow();
      UUID bound = UUID.fromString(copiedPage.widgets().getFirst().binding().get("workflowId"));
      var resolved =
          target.workflows().stream().filter(f -> f.id().equals(bound)).findFirst().orElseThrow();
      assertEquals(
          i < 2 ? "Ready to save the draft?" : "Save this draft?",
          resolved.steps().getFirst().prompt());
    }
    ScreenValidation.validate(target, store, Set.of(), ScreenValidation.Limits.standard());
  }
}

jQuery(document).ready(function() {
  "use strict";

  var components = [
    "modal-dialog",
    "message-panel",
    "checkbox",
    "fill-info",
    "foreman-history",
    "foreman-other-applications",
    "select-component",
    "string",
    "attachments-multiselect",
    "export-attachments",
    "neighbors-owners",
    "neighbors-edit"
  ];

  _.forEach(components, function(component) {
    ko.components.register(component, {
      viewModel: LUPAPISTE[_.capitalize(_.camelCase(component)) + "Model"],
      template: { element: component + "-template"}
    });
  });
});
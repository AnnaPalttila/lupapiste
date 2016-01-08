var docgen = (function () {
  "use strict";

  function displayDocuments(containerSelector, application, documents, authorizationModel, options) {
    function updateOther(select) {
      var otherId = select.attr("data-select-other-id"),
          other = $("#" + otherId, select.parent().parent());
      other.parent().css("visibility", select.val() === "other" ? "visible" : "hidden");
    }
    function initSelectWithOther(i, e) { updateOther($(e)); }
    function selectWithOtherChanged() { updateOther($(this)); }

    var isDisabled = options && options.disabled;
    var docgenDiv = $(containerSelector).empty();

    _.each(documents, function (doc) {
      var schema = doc.schema;
      var docModel = new DocModel(schema, doc, application, authorizationModel, options);
      docModel.triggerEvents();

      docgenDiv.append(docModel.element);

      if (doc.validationErrors) {
        docModel.showValidationResults(doc.validationErrors);
      }

      if (schema.info.repeating && !schema.info["no-repeat-button"] && !isDisabled && authorizationModel.ok("create-doc")) {
        var icon = $("<i>", {"class": "lupicon-circle-plus"});
        var span = $("<span>").text( loc(schema.info.name + "._append_label"));
        var btn = $("<button>",
                    {"data-test-id": schema.info.name + "_append_btn", "class": "secondary"})
          .click(function () {
            var self = this;
            ajax
              .command("create-doc", { schemaName: schema.info.name, id: application.id, collection: docModel.getCollection() })
              .success(function (resp) {
                var newDocId = resp.doc;
                var newDocSchema = _.cloneDeep(schema);
                newDocSchema.info.op = null;

                // The new document might contain some default values, get them from backend
                ajax.query("document", {id: application.id, doc: newDocId, collection: docModel.getCollection()})
                  .success(function(data) {
                    var newDoc = data.document;
                    var newElem = new DocModel(newDocSchema, newDoc, application, authorizationModel).element;
                    $(self).before(newElem);
                    $(".sticky", newElem).Stickyfill();
                  })
                  .call();
              })
              .call();
          });
        btn.append( [icon, span]);
        docgenDiv.append(btn);
      }
    });

    $("select[data-select-other-id]", docgenDiv).each(initSelectWithOther).change(selectWithOtherChanged);
    $(".sticky", docgenDiv).Stickyfill();
    window.Stickyfill.rebuild();
  }

  return {
    displayDocuments: displayDocuments
  };

})();

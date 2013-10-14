var repository = (function() {
  "use strict";

  var loadingSchemas = ajax
    .query("schemas")
    .error(function(e) { error("can't load schemas"); })
    .call();

  function findSchema(schemas, name, version) {
    var v = schemas[version] || schemaNotFound(schemas, name, version);
    var s = v[name] || schemaNotFound(schemas, name, version);
    return _.clone(s);
  }

  function schemaNotFound(schemas, name, version) {
    // TODO, now what?
    var message = "unknown schema, name='" + name + "', version='" + version + "'";
    error(message);
    throw message;
  }

  function load(id, pending) {
    var loadingApp = ajax
      .query("application", {id: id})
      .pending(pending)
      .error(function(e) {
        error("Application " + id + " not found", e);
        LUPAPISTE.ModalDialog.open("#dialog-application-load-error");
      })
      .call();
    $.when(loadingSchemas, loadingApp).then(function(schemasResponse, loadingResponse) {
      var schemas = schemasResponse[0].schemas,
          loading = loadingResponse[0],
          application = loading.application;

      if (application) {
        _.each(application.documents || [], function(doc) {
          var schemaInfo = doc["schema-info"],
              schema = findSchema(schemas, schemaInfo.name, schemaInfo.version);
          schema.info = schemaInfo;
          doc.schema = schema;
        });
        hub.send("application-loaded", {applicationDetails: loading});
      };
    });
  }

  function loaded(pages, f) {
    if (!_.isFunction(f)) throw "f is not a function: f=" + f;
    hub.subscribe("application-loaded", function(e) {
      if (_.contains(pages, pageutil.getPage())) {
        //TODO: passing details as 2nd param due to application.js hack (details contains the municipality persons)
        f(e.applicationDetails.application, e.applicationDetails);
      }
    });
  }

  function showApplicationList() {
    pageutil.hideAjaxWait();
    window.location.hash = "!/applications";
  }

  // Cannot be changed to use LUPAPISTE.ModalDialog.showDynamicYesNo, because the id is registered with hub.subscribe.
  LUPAPISTE.ModalDialog.newYesNoDialog("dialog-application-load-error",
      loc("error.application-not-found"), loc("error.application-not-accessible"),
      loc("navigation"), showApplicationList, loc("logout"), function() {hub.send("logout");});

  hub.subscribe({type: "dialog-close", id : "dialog-application-load-error"}, function() {
    showApplicationList();
  });

  return {
    load: load,
    loaded: loaded,
    schemas: loadingSchemas.promise() // for debugging
  };

})();

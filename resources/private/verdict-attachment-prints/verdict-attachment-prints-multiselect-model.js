
(function() {
  "use strict";

  var model = {
    selectingMode: ko.observable(false),
    authorization: undefined,
    appModel: undefined,
    filteredAttachments: undefined,

    setAttachmentsAsVerdictAttachment: function(selectedAttachmentsIds, unSelectedAttachmentsIds) {
      var id = model.appModel.id();
      ajax.command("set-attachments-as-verdict-attachment", {
        id: id,
        lang: loc.getCurrentLanguage(),
        selectedAttachmentIds: selectedAttachmentsIds,
        unSelectedAttachmentIds: unSelectedAttachmentsIds
      })
      .success(function() {
        window.location.hash = "!/application/" + id + "/attachments";
        repository.load(id);
      })
      .error(function() {
        notify.error(loc("error.dialog.title"), loc("attachment.set-attachments-as-verdict-attachment.error"));
        repository.load(id);
      })
      .call();
    },

    cancelSelecting: function() {
      var id = model.appModel.id();
      model.selectingMode(false);
      model.appModel = null;
      model.attachments = null;
      model.authorization = null;

      window.location.hash="!/application/" + id + "/attachments";
      repository.load(id);
    }
  };

  function filterAttachments(attachments) {
    return _(attachments)
      .each(function(a) {
        a.selected = a.forPrinting ? a.forPrinting : false;
      })
      .value();
  }

  function initMarking(appModel) {
    model.appModel = appModel;
    model.filteredAttachments = filterAttachments(ko.mapping.toJS(appModel.attachments()));
    model.authorization = lupapisteApp.models.applicationAuthModel;

    window.location.hash="!/verdict-attachments-select/" + model.appModel.id();
  }

  hub.onPageLoad("verdict-attachments-select", function() {
    if ( pageutil.subPage() ) {
      if ( !model.appModel || model.appModel.id() !== pageutil.subPage() ) {
        // refresh
        model.selectingMode(false);

        var appId = pageutil.subPage();
        repository.load(appId, null, function(application) {
          lupapisteApp.setTitle(application.title);

          model.authorization = lupapisteApp.models.applicationAuthModel;
          model.appModel = lupapisteApp.models.application;

          ko.mapping.fromJS(application, {}, model.appModel);

          model.filteredAttachments = filterAttachments(application.attachments);

          model.selectingMode(true);
        });
      } else { // appModel already initialized, show the multiselect view
        model.selectingMode(true);
        lupapisteApp.setTitle(model.appModel.title());
      }
    } else {
      error("No application ID provided for verdict attachments multiselect");
      LUPAPISTE.ModalDialog.open("#dialog-application-load-error");
    }
  });

  hub.onPageUnload("verdict-attachments-select", function() {
    model.selectingMode(false);
  });

  hub.subscribe("start-marking-verdict-attachments", function(param) {
    initMarking(param.application);
  });


  $(function() {
    $("#verdict-attachments-select").applyBindings(model);
  });
})();

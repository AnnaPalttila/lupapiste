LUPAPISTE.AttachmentsTabModel = function(appModel) {
  "use strict";

  var self = this;

  self.appModel = appModel;

  var postVerdictStates = {verdictGiven:true, constructionStarted:true, closed:true};

  self.preAttachmentsByOperation = ko.observableArray();
  self.postAttachmentsByOperation = ko.observableArray();

  self.unsentAttachmentsNotFound = ko.observable(false);
  self.sendUnsentAttachmentsButtonDisabled = ko.computed(function() {
    return self.appModel.pending() || self.appModel.processing() || self.unsentAttachmentsNotFound();
  });

  self.showHelp = ko.observable(false);

  function getPreAttachments(source) {
    return _.filter(source, function(attachment) {
          return !postVerdictStates[attachment.applicationState];
      });
  }

  function getPostAttachments(source) {
    return _.filter(source, function(attachment) {
          return postVerdictStates[attachment.applicationState];
      });
  }

  function unsentAttachmentFound(attachments) {
    return _.some(attachments, function(a) {
      var lastVersion = _.last(a.versions);
      return lastVersion &&
             (!a.sent || lastVersion.created > a.sent) &&
             (!a.target || (a.target.type !== "statement" && a.target.type !== "verdict"));
    });
  }

  self.toggleHelp = function() {
    self.showHelp(!self.showHelp());
  };

  self.refresh = function(appModel) {
    self.appModel = appModel;
    var rawAttachments = ko.mapping.toJS(appModel.attachments);

    var preAttachments = getPreAttachments(rawAttachments);
    var postAttachments = getPostAttachments(rawAttachments);

    // pre verdict attachments are not editable after verdict has been given
    var preGroupEditable = currentUser.isAuthority() || !postVerdictStates[appModel.state()];
    var preGrouped = attachmentUtils.getGroupByOperation(preAttachments, preGroupEditable, self.appModel.allowedAttachmentTypes());
    var postGrouped = attachmentUtils.getGroupByOperation(postAttachments, true, self.appModel.allowedAttachmentTypes());

    self.preAttachmentsByOperation(preGrouped);
    self.postAttachmentsByOperation(postGrouped);

    self.unsentAttachmentsNotFound(!unsentAttachmentFound(rawAttachments));
  };

  self.sendUnsentAttachmentsToBackingSystem = function() {
    ajax
      .command("move-attachments-to-backing-system", {id: self.appModel.id(), lang: loc.getCurrentLanguage()})
      .success(self.appModel.reload)
      .processing(self.appModel.processing)
      .pending(self.appModel.pending)
      .call();
  };

  self.newAttachment = function() {
    attachment.initFileUpload({
      applicationId: self.appModel.id(),
      attachmentId: null,
      attachmentType: null,
      typeSelector: true,
      opSelector: true
    });
  };

  self.copyOwnAttachments = function(model) {
    ajax.command("copy-user-attachments-to-application", {id: self.appModel.id()})
      .success(self.appModel.reload)
      .processing(self.appModel.processing)
      .call();
    return false;
  };

  self.deleteSingleAttachment = function(a) {
    var attId = _.isFunction(a.id) ? a.id() : a.id;
    LUPAPISTE.ModalDialog.showDynamicYesNo(
      loc("attachment.delete.header"),
      loc("attachment.delete.message"),
      {title: loc("yes"),
       fn: function() {
        ajax.command("delete-attachment", {id: self.appModel.id(), attachmentId: attId})
          .success(function() {
            self.appModel.reload();
          })
          .error(function (e) {
            LUPAPISTE.ModalDialog.showDynamicOk(loc("error.dialog.title"), loc(e.text));;
          })
          .processing(self.appModel.processing)
          .call();
        return false;
      }},
      {title: loc("no")});
  };

  self.startStamping = function() {
    hub.send('start-stamping', {application: self.appModel});
  };

  self.attachmentTemplatesModel = new function() {
    var templateModel = this;
    templateModel.ok = function(ids) {
      ajax.command("create-attachments", {id: self.appModel.id(), attachmentTypes: ids})
        .success(function() { repository.load(self.appModel.id()); })
        .complete(LUPAPISTE.ModalDialog.close)
        .call();
    };

    templateModel.init = function() {
      templateModel.selectm = $("#dialog-add-attachment-templates .attachment-templates").selectm();
      templateModel.selectm.ok(templateModel.ok).cancel(LUPAPISTE.ModalDialog.close);
      return templateModel;
    };

    templateModel.show = function() {
      var data = _.map(self.appModel.allowedAttachmentTypes(), function(g) {
        var groupId = g[0];
        var groupText = loc(["attachmentType", groupId, "_group_label"]);
        var attachemntIds = g[1];
        var attachments = _.map(attachemntIds, function(a) {
          var id = {"type-group": groupId, "type-id": a};
          var text = loc(["attachmentType", groupId, a]);
          return {id: id, text: text};
        });
        return [groupText, attachments];
      });
      templateModel.selectm.reset(data);
      LUPAPISTE.ModalDialog.open("#dialog-add-attachment-templates");
      return templateModel;
    };
  }();

  hub.subscribe("op-description-changed", function(e) {
    var opid = e['op-id'];
    var desc = e['op-desc'];

    _.each(self.appModel.attachments(), function(attachment) {
      if ( ko.unwrap(attachment.op) && attachment.op.id() === opid ) {
        attachment.op.description(desc);
      }
    });

    self.refresh(self.appModel);
  });

};

(function() {
  "use strict";

  var applicationId = null;
  var statementId = null;

  // this function is mutated over in the attachement.deleteVersion
  var deleteAttachmentFromServerProxy;

  function deleteAttachmentFromServer(attachmentId) {
    ajax
      .command("delete-attachment", {id: applicationId, attachmentId: attachmentId})
      .success(function() {
        repository.load(applicationId);
        return false;
      })
      .call();
    return false;
  }

  function StatementModel() {
    var self = this;

    self.data = ko.observable();
    self.application = ko.observable();

    self.statuses = ko.observableArray([]);
    self.selectedStatus = ko.observable();
    self.text = ko.observable();
    self.submitting = ko.observable(false);
    self.dirty = ko.observable(false);
    self.submitLtext = ko.computed(function() {
      if(self.data() && self.data().status()) {
        return "statement.submit-again";
      } else {
        return "statement.submit";
      }
    });

    self.text.subscribe(function(value) {
      if(self.data() && self.data().text && self.data().text() !== value) { self.dirty(true); }
    });

    self.selectedStatus.subscribe(function(value) {
      if(self.data() && self.data().status && self.data().status() !== value) { self.dirty(true); }
    });

    self.clear = function() {
      self.data(null);
      self.application(null);
      self.statuses([]);
      self.selectedStatus(null);
      self.text(null);
      self.dirty(false);
      return self;
    };

    self.refresh = function(application) {
      self.application(ko.mapping.fromJS(application));
      var statement = application.statements && _.find(application.statements, function(statement) { return statement.id === statementId; });
      if(statement) {
        self.data(ko.mapping.fromJS(statement));

        if (!self.dirty()) {
          if (statement.status) {
            self.selectedStatus(statement.status);  // LUPA-482 part II
          }
          if (statement.text) {
            self.text(statement.text);
          }
          self.dirty(false);
        }

        ajax
          .query("get-possible-statement-statuses", {id: applicationId})
          .success(function(resp) {
            var sorted = _(resp.data)
              .map(function(item) { return {id: item, name: loc(["statement", item])}; })
              .sortBy("name")
              .value();
            self.statuses(sorted);
          })
          .call();

      } else {
        pageutil.openPage("404");
      }
    };

    self.openDeleteDialog = function() {
      LUPAPISTE.ModalDialog.showDynamicYesNo(
          loc("statement.delete.header"),
          loc("statement.delete.message"),
          {title: loc("yes"), fn: deleteStatementFromServer},
          {title: loc("no")}
        );
    };

    self.submit = function() {
      self.submitting(true);
      ajax
        .command("give-statement", {id: applicationId, statementId: statementId, status: self.selectedStatus(), text: self.text(), lang: loc.getCurrentLanguage()})
        .success(function() {
          pageutil.openApplicationPage({id: applicationId}, "statement");
          repository.load(applicationId);
          return false;
        })
        .complete(function() { self.submitting(false); })
        .call();
      return false;
    };

    self.disabled = ko.computed(function() {
      return !self.selectedStatus() || !self.text() || self.submitting() || !self.dirty();
    });

    self.canDeleteStatement = function() {
      return authorizationModel.ok("delete-statement");
    };

  }

  function deleteStatementFromServer() {
    ajax
      .command("delete-statement", {id: applicationId, statementId: statementId})
      .success(function() {
        repository.load(applicationId);
        pageutil.openApplicationPage({id: applicationId}, "statement");
        return false;
      })
      .call();
    return false;
  }

  function AttachmentsModel() {
    var self = this;

    self.attachments = ko.observableArray([]);

    self.refresh = function(application) {
      self.attachments(_.filter(application.attachments,function(attachment) {
        return _.isEqual(attachment.target, {type: "statement", id: statementId});
      }));
    };

    self.canDeleteAttachment = function(attachment) {
      return authorizationModel.ok("delete-attachment") &&
             authorizationModel.ok('give-statement') &&
             (!attachment.requestedByAuthority || lupapisteApp.models.currentUser.isAuthority());
    };

    self.canAddAttachment = function() {
      return authorizationModel.ok("upload-attachment") && authorizationModel.ok('give-statement');
    };

    self.deleteAttachment = function(attachmentId) {
      deleteAttachmentFromServerProxy = function() { deleteAttachmentFromServer(attachmentId); };
      LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("attachment.delete.version.header"),
        loc("attachment.delete.version.message"),
        {title: loc("yes"), fn: deleteAttachmentFromServerProxy},
        {title: loc("no")}
      );
    };

    self.newAttachment = function() {
      // created file is authority-file if created by authority
      attachment.initFileUpload({
        applicationId: applicationId,
        attachmentId: null,
        attachmentType: "muut.muu",
        typeSelector: false,
        target: {type: "statement", id: statementId},
        locked: true
      });
      LUPAPISTE.ModalDialog.open("#upload-dialog");
    };
  }

  var statementModel = new StatementModel();
  var authorizationModel = authorization.create();
  var attachmentsModel = new AttachmentsModel();

  repository.loaded(["statement"], function(application) {
    if (applicationId === application.id) {
      authorizationModel.refresh(application, {statementId: statementId}, function() {
        statementModel.refresh(application);
        attachmentsModel.refresh(application);
      });
    }
  });

  hub.onPageLoad("statement", function(e) {
    statementModel.clear();
    applicationId = e.pagePath[0];
    statementId = e.pagePath[1];
    repository.load(applicationId);
  });

  $(function() {
    $("#statement").applyBindings({
      statementModel: statementModel,
      authorization: authorizationModel,
      attachmentsModel: attachmentsModel
    });
  });

})();

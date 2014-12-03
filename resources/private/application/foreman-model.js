LUPAPISTE.ForemanModel = function() {
  "use strict";
  var self = this;

  self.application = null;
  self.email = ko.observable();
  self.error = ko.observable();
  self.processing = ko.observable();
  self.pending = ko.observable();
  self.disabled = ko.computed(function() {
    return self.email() && !util.isValidEmailAddress(self.email());
  });
  self.foremanApplications = ko.observableArray();

  self.refresh = function(application) {
    function loadForemanApplications(linkPermits) {
      self.foremanApplications([]);
      _.forEach(_.pluck(linkPermits, "id"), function(id) {
        ajax
        .query("application", {id: id})
        .success(function(app) {
          var foreman = _.find(app.application.auth, {"role": "foreman"});
          var data = {"state": app.application.state,
                      "id": app.application.id,
                      "email": foreman ? foreman.username : undefined,
                      "firstName": foreman ? foreman.firstName : undefined,
                      "lastName": foreman ? foreman.lastName : undefined};
          self.foremanApplications.push(data);
          self.foremanApplications.sort(function(left, right) {
            return left.id > right.id;
          });
        })
        .error(
          //  invited foreman can't always fetch applicants other foreman appications (if they are not invited to them also)
        )
        .call();
      });
    }

    self.application = application;
    _.defer(function() {
      loadForemanApplications(_.where(application.linkPermitData, { "operation": "tyonjohtajan-nimeaminen" }));
    });
  };

  self.inviteForeman = function() {
    LUPAPISTE.ModalDialog.open("#dialog-invite-foreman");
  };

  self.openApplication = function(id) {
    repository.load(id);
    window.location.hash = "!/application/" + id;
  };

  self.submit = function() {
    self.error(undefined);

    function inviteToApplication(id, cb, errorCb) {
      if (!errorCb) {
        errorCb = cb;
      }

      ajax.command("invite-with-role", { id: id,
                               documentName: "",
                               documentId: "",
                               path: "",
                               email: self.email(),
                               title: "",
                               text: "",
                               role: "foreman" })
        .processing(self.processing)
        .pending(self.pending)
        .success(function(data) {
          cb(data);
        })
        .error(function(err) {
          errorCb(err);
        })
        .call();
    }

    function createApplication() {
      // 2. create "tyonjohtajan ilmoitus" application
      ajax.command("create-foreman-application", { id: self.application.id })
        .processing(self.processing)
        .pending(self.pending)
        .success(function(data) {
          // 3. invite foreman to new application
          if (self.email()) {
            inviteToApplication(data.id, function() {
              LUPAPISTE.ModalDialog.close();
              // 4. open new application
              self.openApplication(data.id);
            }, function(err) {
              self.error(loc(err.text));
            });
          } else {
            LUPAPISTE.ModalDialog.close();
            // 4. open new application
            self.openApplication(data.id);
          }
        })
        .error(function(err) {
          self.error(loc(err.text));
        })
        .call();
    }
    // 1. invite foreman to current application (new role-parameter to invite command)
    if (self.email()) {
      inviteToApplication(self.application.id, createApplication, createApplication);
    } else {
      createApplication();
    }
    return false;
  };
};

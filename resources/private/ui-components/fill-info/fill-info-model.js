LUPAPISTE.FillInfoModel = function(params) {
  "use strict";
  var self = this;

  self.authorization = lupapisteApp.models.applicationAuthModel;

  self.fillUserInfo = function () {
    ajax
      .command("set-current-user-to-document", params)
      .success(function () {
        repository.load(params.id);
        hub.send("documentChangedInBackend", {
          documentName: params.documentName
        });
      })
      .call();
  };
};


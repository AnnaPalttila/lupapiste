LUPAPISTE.StatementReplyRequestModel = function(params) {
  "use strict";
  var self = this;

  self.tab = "reply-request";

  self.authModel = params.authModel;

  self.applicationTitle = params.applicationTitle;
  self.data = params.data;

  self.text = ko.observable();

  var commands = params.commands;

  // FIXME computed + dispose
  var initSubscription = self.data.subscribe(function() {
    self.text(util.getIn(self.data, ["reply", "saateText"]));
    hub.send("statement::submitAllowed", {tab: self.tab, value: true});
    initSubscription.dispose();
  });

  self.enabled = ko.pureComputed(function() {
    return self.authModel.ok(commands["submit"]);
  });

  self.submitAuthorized = ko.pureComputed(function() {
    return self.authModel.ok("request-for-statement-reply");
  });

  // FIXME computed + dispose
  self.text.subscribe(function(value) {
    if(value && util.getIn(self.data, ["reply", "saateText"]) !== value) {
      hub.send("statement::changed", {tab: self.tab, path: ["reply", "saateText"], value: value});
    }
  });

};

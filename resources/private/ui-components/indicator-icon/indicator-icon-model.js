LUPAPISTE.IndicatorIconModel = function() {
  "use strict";
  var self = this;

  self.showIndicator = ko.observable().extend({notify: "always"});
  self.indicatorStyle = ko.observable("neutral");
  var message = ko.observable("");
  var timerId;

  self.iconStyle = ko.pureComputed(function() {
    if (self.indicatorStyle() === "positive") {
      return "lupicon-circle-check";
    }
    else if (self.indicatorStyle() === "negative") {
      return "lupicon-circle-attention";
    }
  });

  self.indicatorMessage = ko.pureComputed(function() {
    if (message()) {
      return message();
    }
    else if (self.indicatorStyle() === "positive") {
      return "form.saved";
    }
    else if (self.indicatorStyle() === "negative") {
      return "form.err";
    }
  });

  self.showIndicator.subscribe(function(val) {
    if (self.indicatorStyle() === "negative" && timerId) {
      // stop timer if indicator was set negative during positive indicator hide was delayed
      clearTimeout(timerId);
      timerId = undefined;
    } else if (val) {
      // automatically hide indicator
      timerId = _.delay(function() {
        self.showIndicator(false);
        timerId = undefined;
      }, 2000);
    }
  });

  hub.subscribe("indicator-icon", 
    _.throttle( function(e) {
      message(e.message);
      self.indicatorStyle(e.style);
      self.showIndicator(true);
    }, 10000)
  );
};

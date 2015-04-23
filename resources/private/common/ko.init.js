;(function($) {
  "use strict";

  //
  // initialize Knockout validation
  //

  ko.validation.init({
    insertMessages: true,
    decorateElement: true,
    errorMessageClass: "error-message",
    parseInputAttributes: true,
    messagesOnModified: true,
    messageTemplate: "error-template",
    registerExtenders: true
  });

  // As of 2013-03-25, the following keys are missing:
  // ["min", "max", "pattern", "step", "date", "dateISO", "digit", "phoneUS", "notEqual", "unique"];
  ko.validation.localize(loc.getErrorMessages());

  ko.validation.rules.validPassword = {
    validator: function (val) {
      return val && util.isValidPassword(val);
    },
    message: loc("error.password.minlength")
  };

  ko.validation.rules.y = {
    validator: function(y) {
      return _.isBlank(y) || util.isValidY(y);
    },
    message: loc("error.invalidY")
  };

  ko.validation.rules.ovt = {
    validator: function(ovt) {
      return _.isBlank(ovt) || util.isValidOVT(ovt);
    },
    message: loc("error.invalidOVT")
  };

  ko.validation.registerExtenders();

  ko.bindingHandlers.dateString = {
    update: function(element, valueAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      if (value) { $(element).text(moment(value).format("D.M.YYYY")); }
    }
  };

  ko.bindingHandlers.dateTimeString = {
    update: function(element, valueAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      $(element).text(moment(value).format("D.M.YYYY HH:mm"));
    }
  };

  function localized(fnName, element, valueAccessor) {
    var e$ = $(element);
    var fn = e$[fnName];
    var value = ko.utils.unwrapObservable(valueAccessor());
    if (value) {
      var v = loc(value);
      fn.call(e$, (v ? v : "$$EMPTY_LTEXT$$"));
      if (v) {
        e$.removeClass("ltext-error");
      } else {
        e$.addClass("ltext-error");
      }
    } else {
      // value is null or undefined, show it as empty string. Note that this
      // does not mean that the localization would be missing. It's just that
      // the actual value to use for localization is not available at this time.
      fn.call(e$, "");
      e$.removeClass("ltext-error");
    }
  }

  ko.bindingHandlers.ltext = {
    update: _.partial(localized, "text")
  };

  ko.bindingHandlers.lhtml = {
    update: _.partial(localized, "html")
  };

  ko.bindingHandlers.fullName = {
    update: function(element, valueAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var fullName = "";
      if (value) {
        if (value.lastName) { fullName += _.isFunction(value.lastName) ? value.lastName() : value.lastName; }
        if (value.firstName && value.lastName) { fullName += "\u00a0"; }
        if (value.firstName) { fullName += _.isFunction(value.firstName) ? value.firstName() : value.firstName; }
      }
      $(element).text(fullName);
    }
  };

  ko.bindingHandlers.size = {
    update: function(element, valueAccessor) {
      var v = ko.utils.unwrapObservable(valueAccessor());

      if (!v || v.length === 0) {
        $(element).text("");
        return;
      }

      var value = parseFloat(v);
      var unit = "B";

      if (value > 1200.0) {
        value = value / 1024.0;
        unit = "kB";
      }

      if (value > 1200.0) {
        value = value / 1024.0;
        unit = "MB";
      }
      if (value > 1200.0) {
        value = value / 1024.0;
        unit = "GB";
      }
      if (value > 1200.0) {
        value = value / 1024.0;
        unit = "TB"; // Future proof?
      }

      if (unit !== "B") {
        value = value.toFixed(1);
      }

      $(element).text(value + "\u00a0" + unit);
    }
  };

  ko.bindingHandlers.version = {
    update: function(element, valueAccessor) {
      var verValue = ko.utils.unwrapObservable(valueAccessor());

      var version = "";
      if (verValue && (verValue.major || verValue.minor)) {
        if (typeof verValue.major === "function") {
          version = verValue.major() + "." + verValue.minor();
        } else {
          version = verValue.major + "." + verValue.minor;
        }
      }
      $(element).text(version);
    }
  };

  ko.bindingHandlers.propertyId = {
    update: function(element, valueAccessor) {
      var v = ko.utils.unwrapObservable(valueAccessor()),
          f = util.prop.toHumanFormat(v);
      $(element).text(f ? f : "");
    }
  };

  ko.bindingHandlers.datepicker = {
    init: function(element, valueAccessor, allBindingsAccessor) {
      //initialize datepicker with some optional options
      var options = allBindingsAccessor().datepickerOptions || {};
      $(element).datepicker(options);

      //handle the field changing
      ko.utils.registerEventHandler(element, "change", function () {
        var observable = valueAccessor();
        observable($(element).datepicker("getDate"));
      });

      //handle disposal (if KO removes by the template binding)
      ko.utils.domNodeDisposal.addDisposeCallback(element, function() {
        $(element).datepicker("destroy");
      });
    },
    update: function(element, valueAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());

      //handle date data
      if (String(value).indexOf("/Date(") === 0) {
        value = new Date(parseInt(value.replace(/\/Date\((.*?)\)\//gi, "$1"), 10));
      }

      var current = $(element).datepicker("getDate");

      if (value - current !== 0) {
        $(element).datepicker("setDate", value);
      }
    }
  };

  ko.bindingHandlers.saveIndicator = {
    init: function(element, valueAccessor, allBindingsAccessor) {
      var bindings = ko.utils.unwrapObservable(allBindingsAccessor());
      var text$ = $("<span>").addClass("text");
      $(element).addClass("form-indicator");
      $(element).append(text$);
      $(element).append($("<span>").addClass("icon"));
      if (bindings.label !== false) {
        text$.text(loc("form.saved"));
      }
    },
    update: function(element, valueAccessor, allBindingsAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var bindings = ko.utils.unwrapObservable(allBindingsAccessor());
      var name = bindings.name;
      var type = value ? (value.type ? ko.unwrap(value.type) : "saved") : undefined;
      var valueName = value ? ko.unwrap(value.name) : undefined;

      if (_.isString(value)) {
        valueName = value;
      }

      $(element).addClass("form-input-" + type);

      if (value && valueName === name || value && name === undefined) {
        $(element).fadeIn(200);
        setTimeout(function() {
          $(element).fadeOut(200, function() {
            $(element).removeClass("form-input-" + type);
          });
        }, 4000);
      }
    }
  };

  ko.bindingHandlers.transition = {
    init: function(element, valueAccessor, allBindings) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var className = allBindings()["class"];
      if (className) {
        $(element).toggleClass(className, value);
      }
    },
    update: function(element, valueAccessor, allBindings) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var className = allBindings()["class"];
      var type = allBindings().type;
      if (type) {
        $(element)[type + "Toggle"](1000);
      } else {
        $(element).toggleClass(className, value, 100);
      }
    }
  };

  ko.bindingHandlers.slider = {
    update: function(element, valueAccessor, allBindingsAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var bindings = ko.utils.unwrapObservable(allBindingsAccessor());
      var duration = bindings.duration || 100;
      var easing = bindings.easing || "swing";
      if (value) {
        $(element).slideDown(duration, easing);
      } else {
        $(element).slideUp(duration, easing);
      }
    }
  };

  ko.bindingHandlers.fader = {
    update: function(element, valueAccessor, allBindingsAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var bindings = ko.utils.unwrapObservable(allBindingsAccessor());
      var duration = bindings.duration || 100;
      if (value) {
        $(element).fadeIn({duration: duration, queue: false});
      } else {
        $(element).fadeOut({duration: duration, queue: false});
      }
    }
  };

  ko.bindingHandlers.drill = {
    init: function(element) {
      $(element).addClass("icon");
    },
    update: function(element, valueAccessor, allBindingsAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      var bindings = ko.utils.unwrapObservable(allBindingsAccessor());
      var color = bindings.color || "white";
      if (value) {
        $(element).addClass("drill-down-" + color);
        $(element).removeClass("drill-right-" + color);
      } else {
        $(element).removeClass("drill-down-" + color);
        $(element).addClass("drill-right-" + color);
      }
    }
  };

  ko.bindingHandlers.toggleClick = {
    init: function(element, valueAccessor) {
      var value = valueAccessor();

      ko.utils.registerEventHandler(element, "click", function() {
        value(!value());
      });
    }
  };

  $.fn.applyBindings = function(model) {
    if (!this.length) {
      warn(this.selector + " didn't match any elements");
      return this;
    }
    if (this.length > 1) {
      warn("Apply bindings to " + this.length + " nodes");
    }
    _.each(this, _.partial(ko.applyBindings, model));
    return this;
  };

  ko.bindingHandlers.readonly = {
    update: function(element, valueAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      element.readOnly = value;
    }
  };

  ko.bindingHandlers.documentEvent = {
    init: function(element, valueAccessor) {
      var value = ko.utils.unwrapObservable(valueAccessor());
      $(document).keyup(function(e) {
        if (e.keyCode === value.key) {
          if (value.keypress) {
            value.keypress();
          }
        }
      });
    }
  };
})(jQuery);

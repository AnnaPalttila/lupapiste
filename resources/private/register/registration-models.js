/**
 * Usage: Create new instance and bind 'model' property to Knockout bindings.
 * Username and password from user input are given to afterSuccessFn function.
 * Used keys can be set with optional last constructor parameter.
 */
LUPAPISTE.RegistrationModel = function(commandName, afterSuccessFn, errorSelector, ks) {
  "use strict";

  var self = this;

  self.keys = ks || ["stamp", "personId", "firstName", "lastName", "email", "confirmEmail", "street", "city", "zip", "phone", "password", "confirmPassword", "street", "zip", "city", "allowDirectMarketing", "rakentajafi"];

  self.plainModel = {
    personId: ko.observable(""),
    firstName: ko.observable(""),
    lastName: ko.observable(""),
    stamp: ko.observable(""),
    tokenId: ko.observable(""),
    street: ko.observable("").extend({required: true}),
    city: ko.observable("").extend({required: true}),
    zip: ko.observable("").extend({required: true, number: true, maxLength: 5}),
    phone: ko.observable("").extend({required: true}),
    allowDirectMarketing: ko.observable(false),
    email: ko.observable("").extend({email: true}),
    password: ko.observable("").extend({validPassword: true}),
    rakentajafi: ko.observable(false),
    acceptTerms: ko.observable(false),
    disabled: ko.observable(true),
    pending: ko.observable(false),
    showRakentajafiInfo: function() {
      LUPAPISTE.ModalDialog.open("#dialogRakentajafi");
    },
    submit: function() {
      var error$ = $(errorSelector);
      error$.text("");
      self.plainModel.pending(true);
      self.plainModel.disabled(true);
      ajax.command(commandName, self.json())
        .success(function() {
          var email = _.clone(self.plainModel.email());
          var password = _.clone(self.plainModel.password());
          self.reset();
          self.plainModel.email(email);
          self.plainModel.pending(false);
          self.plainModel.disabled(false);
          afterSuccessFn(email, password);
        })
        .error(function(e) {
          self.plainModel.pending(false);
          self.plainModel.disabled(false);
          error$.text(loc(e.text));
        })
        .call();
      return false;
    },
    cancel: function() {
      LUPAPISTE.ModalDialog.showDynamicYesNo(
        loc("areyousure"),
        loc("register.confirm-cancel"),
        {title: loc("yes"),
         fn: function() {
          self.reset();
          window.location.hash = "";
        }},
        {title: loc("no")}
      );
    }
  };
  self.plainModel.confirmPassword = ko.observable().extend({equal: self.plainModel.password});
  self.plainModel.confirmEmail = ko.observable().extend({equal: self.plainModel.email});

  self.model = ko.validatedObservable(self.plainModel);
  self.model.isValid.subscribe(function(valid) {
    self.plainModel.disabled(!valid || !self.plainModel.acceptTerms());
  });
  self.plainModel.acceptTerms.subscribe(function() {
    self.plainModel.disabled(!self.model.isValid() || !self.plainModel.acceptTerms());
  });

  self.json = function() {
    var d = {};
    _.forEach(self.keys, function(key) {
      d[key] = self.plainModel[key]() || null;
    });

    d.confirmPassword = null;
    d.confirmEmail = null;
    return d;
  };

  self.reset = function() {
    _.forEach(self.keys, function(key) {
      if (self.plainModel[key] !== undefined) {
        self.plainModel[key]("");
        if (self.plainModel[key].isModified) {
          self.plainModel[key].isModified(false);
        }
      }
    });
    self.plainModel.tokenId(pageutil.subPage());
    return false;
  };

  self.setVetumaData = function(data) {
    self.plainModel.personId(data.userid);
    self.plainModel.firstName(data.firstName);
    self.plainModel.lastName(data.lastName);
    self.plainModel.stamp(data.stamp);
    self.plainModel.city((data.city || ""));
    self.plainModel.zip((data.zip || ""));
    self.plainModel.street((data.street || ""));
  };

  self.setPhone = function(phone) {
    self.plainModel.phone(phone);
  };

  self.setEmail = function(email) {
    self.plainModel.email(email);
    self.plainModel.confirmEmail(email);
  };

};

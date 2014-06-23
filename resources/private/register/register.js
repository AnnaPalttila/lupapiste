;(function() {
  "use strict";

  var registrationModel = new LUPAPISTE.RegistrationModel("register-user", function() {window.location.hash = "!/register3";}, "#register-email-error");
  var statusModel = new LUPAPISTE.StatusModel();

  hub.onPageChange("register", function() {
    var urlPrefix = "/app/" + loc.getCurrentLanguage() + "/welcome";
    $.get("/api/vetuma", {success: urlPrefix + "#!/register2",
                          cancel:  urlPrefix + "#!/register/cancel",
                          error:   urlPrefix + "#!/register/error"}, function(d) {
      $("#vetuma-register")
        .html(d).find(":submit").addClass("btn btn-primary")
                                .attr("value",loc("register.action"))
                                .attr("id", "vetuma-init");
    });
    statusModel.subPage(pageutil.subPage());
  });

  hub.onPageChange("register2", function() {
    registrationModel.reset();
    ajax.get("/api/vetuma/user")
      .raw(true)
      .success(function(data) {
        if (data) {
          registrationModel.setVetumaData(data);
        } else {
          window.location.hash = "!/register";
        }
      })
      .error(function(e){$("#register-email-error").text(loc(e.text));})
      .call();
  });

  $(function(){
    $("#register").applyBindings(statusModel);
    $("#register2").applyBindings(registrationModel.model);
    $("#register3").applyBindings(registrationModel.model);
  });

})();

*** Settings ***

Documentation   Teppo resets password
Resource       ../../common_resource.robot

*** Test Cases ***

Unable to log in
  Go to login page
  Login fails  teppo@example.com  teppo123

Go to reset password page
  Click Link  Oletko unohtanut salasanasi?
  Wait Until  Element Should Be Visible  reset
  Page Should Contain  Salasanan vaihtaminen

Fill in wrong email
  Input text  email  teppo@exaple.com
  Click enabled by test id  reset-send
  Wait Until  Page Should Contain  Antamaasi sähköpostiosoitetta ei löydy järjestelmästä.

Fill in right email
  Input text  email  teppo@example.com
  Click enabled by test id  reset-send
  Wait Until  Page Should Not Contain  Antamaasi sähköpostiosoitetta ei löydy järjestelmästä.

Email was send
  Wait Until  Page Should Contain  Sähköposti lähetetty
  Go to  ${SERVER}/api/last-email
  Page Should Contain  teppo@example.com
  ## First link
  Click link  xpath=//a

Reset password page opens
  Wait Until  Page Should Contain  Salasanan vaihtaminen

Fill in the new password
  Input text  xpath=//section[@id='setpw']//input[@placeholder='Uusi salasana']  teppo123
  Element Should Be Disabled  xpath=//section[@id='setpw']//button
  Input text  xpath=//section[@id='setpw']//input[@placeholder='Salasana uudelleen']  teppo123
  Wait Until  Element Should Be Enabled  xpath=//section[@id='setpw']//button
  Click Element  xpath=//section[@id='setpw']//button
  Go to login page

Can not login with the old password
  Login fails  teppo@example.com  teppo69

Can login with the new password
  User logs in  teppo@example.com  teppo123  Teppo Nieminen
  Logout

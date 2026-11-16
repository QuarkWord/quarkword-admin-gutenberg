// SPDX-License-Identifier: GPL-2.0-or-later
// The screen of a signed-out user: one button that leaves for the sign-in server and comes back to this address.

/** Shows the sign-in screen in `main`; the button signs in and returns to the current address. */
export function renderSignIn(main, auth) {
  const section = document.createElement("section");
  section.className = "qp-signin";
  section.setAttribute("aria-labelledby", "qp-signin-title");
  const heading = document.createElement("h1");
  heading.id = "qp-signin-title";
  heading.textContent = "Log In";
  const button = document.createElement("button");
  button.type = "button";
  button.className = "button button-primary";
  button.textContent = "Log in with QuarkPress";
  button.addEventListener("click", () => {
    button.disabled = true;
    auth.signIn(location.href).catch((failure) => {
      button.disabled = false;
      console.warn("QuarkPress admin: the sign-in could not start.", failure);
    });
  });
  section.append(heading, button);
  main.replaceChildren(section);
  return heading;
}

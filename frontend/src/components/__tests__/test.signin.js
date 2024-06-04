import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SignInComponent from "../signin";

describe("SignInComponent", () => {
  it("renders signup and signin tabs", () => {
    render(<SignInComponent />);

    expect(screen.getByText("Sign Up")).toBeInTheDocument();
    expect(screen.getByText("Sign In")).toBeInTheDocument();
  });

  it("switches between signup and signin tabs", () => {
    render(<SignInComponent />);

    const signupTab = screen.getByText("Sign Up");
    const signinTab = screen.getByText("Sign In");

    userEvent.click(signinTab);
    expect(screen.queryByText("Username")).toHaveTextContent("Username"); // Check content change

    userEvent.click(signupTab);
    expect(screen.queryByText("Username")).toHaveTextContent("Username"); // Check content change back
  });

  it("updates signup email on input change", () => {
    render(<SignInComponent />);

    const emailInput = screen.getByLabelText("Username");
    userEvent.type(emailInput, "test@example.com");

    expect(screen.getByDisplayValue("test@example.com")).toBeInTheDocument();
  });
});

import axios from "axios";
import { render, fireEvent, waitFor } from "@testing-library/react";
import { Main } from "./Main";

jest.mock("axios");

describe("Main component", () => {
  afterEach(() => {
    jest.clearAllMocks();
  });

  it("should save transaction on handleSaveTransaction", async () => {
    const { getByText, getByLabelText } = render(<Main />);

    fireEvent.change(getByLabelText("Date"), {
      target: { value: "2024-05-22" },
    });
    fireEvent.change(getByLabelText("Category"), { target: { value: "rent" } });
    fireEvent.change(getByLabelText("Amount"), { target: { value: "100" } });
    fireEvent.change(getByLabelText("Description"), {
      target: { value: "Test transaction" },
    });

    fireEvent.submit(getByText("Save Transaction"));

    await waitFor(() => {
      expect(axios.post).toHaveBeenCalledTimes(1);
      expect(axios.post).toHaveBeenCalledWith();
    });
  });
});

const { pool } = require("../config/db");

const saveExpense = async (req, res) => {
  const { date, category, amount, description, userId } = req.body;

  if (!date || !category || !amount || !userId) {
    return res
      .status(400)
      .json({ error: "Date, category, amount, and userId are required." });
  }

  try {
    const result = await pool.query(
      "INSERT INTO expenses (date, category, amount, description, userId) VALUES ($1, $2, $3, $4, $5) RETURNING *",
      [date, category, amount, description, userId]
    );

    res.status(201).json(result.rows[0]);
  } catch (error) {
    // console.error("Error saving expense:", error);
    res.status(500).json({ error: "Internal server error" });
  }
};

const getExpense = async (req, res) => {
  const { userId } = req.params;

  try {
    const result = await pool.query(
      "SELECT * FROM expenses WHERE userId = $1",
      [userId]
    );

    res.status(200).json(result.rows);
  } catch (error) {
    // console.error("Error retrieving expenses:", error);
    res.status(500).json({ error: "Internal server error" });
  }
};

const getIncome = async (req, res) => {
  const { userId } = req.params;
  try {
    const result = await pool.query(
      "select totalincome from user_income WHERE userid = $1",
      [userId]
    );

    res.status(200).json(result.rows);
  } catch (error) {
    // console.error("Error retrieving expenses:", error);
    res.status(500).json({ error: "Internal server error" });
  }
};
const updateIncome = async (req, res) => {
  const { userId } = req.params;
  const { newIncome } = req.body;

  if (!newIncome || !userId) {
    return res.status(400).json({ error: "Income and userId are required." });
  }

  try {
    const checkResult = await pool.query(
      "SELECT * FROM user_income WHERE userid = $1;",
      [userId]
    );

    if (checkResult.rows.length === 0) {
      const insertResult = await pool.query(
        "INSERT INTO user_income (userid, totalincome) VALUES ($1, $2) RETURNING *;",
        [userId, newIncome]
      );
      return res.status(201).json(insertResult.rows[0]);
    } else {
      const updateResult = await pool.query(
        "UPDATE user_income SET totalincome = $1 WHERE userid = $2 RETURNING *;",
        [newIncome, userId]
      );
      return res.status(200).json(updateResult.rows[0]);
    }
  } catch (error) {
    // console.error("Error updating income:", error);
    res.status(500).json({ error: "Internal server error" });
  }
};

const deleteExpense = async (req, res) => {
  const { id } = req.params;

  try {
    const result = await pool.query(
      "DELETE FROM expenses WHERE id = $1 RETURNING *",
      [id]
    );

    if (result.rowCount === 0) {
      return res.status(404).json({ error: "Expense not found" });
    }

    res.status(200).json(result.rows[0]);
  } catch (error) {
    // console.error("Error deleting expense:", error);
    res.status(500).json({ error: "Internal server error" });
  }
};

const updateExpense = async (req, res) => {
  const { id, date, category, amount, description, userid } = req.body;

  if (!date || !category || !amount || !userid) {
    return res
      .status(400)
      .json({ error: "Date, category, amount, and userId are required." });
  }

  try {
    const result = await pool.query(
      "UPDATE expenses SET date = $1, category = $2, amount = $3, description = $4, userId = $5 WHERE id = $6 RETURNING *",
      [date, category, amount, description, userid, id]
    );

    if (result.rowCount === 0) {
      return res.status(404).json({ error: "Expense not found" });
    }

    res.status(200).json(result.rows[0]);
  } catch (error) {
    // console.error("Error updating expense:", error);
    res.status(500).json({ error: "Internal server error" });
  }
};

module.exports = {
  saveExpense,
  getExpense,
  deleteExpense,
  updateExpense,
  getIncome,
  updateIncome,
};

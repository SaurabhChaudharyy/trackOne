const generateToken = require("../config/generateToken");
const bcrypt = require("bcryptjs");
const { pool } = require("../config/db");

const registerUser = async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return res.status(400).json({
        message: "Email and Password are required!",
      });
    }

    const userExists = await pool.query(
      "SELECT * FROM users WHERE email = $1",
      [email]
    );
    if (userExists.rows.length > 0) {
      return res.status(400).json({
        message: "User already exists!",
      });
    }

    const hashedPassword = await bcrypt.hash(password, 10);
    console.log(email);
    console.log(hashedPassword);
    const newUser = await pool.query(
      "INSERT INTO users (email, password) VALUES ($1, $2) RETURNING *",
      [email, hashedPassword]
    );

    const token = generateToken(newUser.rows[0].email);
    res.status(201).json({
      message: "User registered successfully",
      token,
    });
  } catch (err) {
    console.log(err);
  }
};

const authUser = async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return res
        .status(400)
        .json({ message: "email and password are required" });
    }

    const userExists = await pool.query(
      "SELECT * FROM users WHERE email = $1",
      [email]
    );
    const getId = await pool.query("SELECT id  FROM users WHERE email = $1", [
      email,
    ]);
    const userId = getId.rows[0].id;
    const user = userExists.rows[0];
    if (!user) {
      return res.status(400).json({ message: "Invalid email or password" });
    }

    const isPasswordValid = await bcrypt.compare(password, user.password);
    if (!isPasswordValid) {
      return res.status(400).json({ message: "Invalid email or password" });
    }

    const token = generateToken(email);
    res
      .status(200)
      .json({ message: "Signin successful", token, userId: userId });
  } catch (err) {
    console.log(err);
  }
};

module.exports = { registerUser, authUser };

"use client";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import {
  DropdownMenuTrigger,
  DropdownMenuItem,
  DropdownMenuContent,
  DropdownMenu,
} from "@/components/ui/dropdown-menu";
import {
  CardDescription,
  CardTitle,
  CardHeader,
  Card,
  CardContent,
} from "@/components/ui/card";
import {
  DrawerTrigger,
  DrawerTitle,
  DrawerDescription,
  DrawerHeader,
  DrawerClose,
  DrawerFooter,
  DrawerContent,
  Drawer,
} from "@/components/ui/drawer";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import {
  SelectValue,
  SelectTrigger,
  SelectItem,
  SelectContent,
  Select,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  TableHead,
  TableRow,
  TableHeader,
  TableCell,
  TableBody,
  Table,
} from "@/components/ui/table";
import { ResponsiveBar } from "@nivo/bar";
import { useState, useEffect } from "react";
import axios from "axios";

export function Main() {
  const [transactions, setTransactions] = useState([]);
  const [totalExpenses, setTotalExpenses] = useState(0);
  const [categoryExpenses, setCategoryExpenses] = useState([]);
  const [newTransaction, setNewTransaction] = useState({
    date: "",
    category: "",
    amount: "",
    description: "",
    userId: 0,
  });
  const [totalIncome, setTotalIncome] = useState(0);
  const [editing, setEditing] = useState(false);
  const [newIncome, setNewIncome] = useState(totalIncome);
  const [balance, setBalance] = useState(0);
  const [editingTransaction, setEditingTransaction] = useState(null);

  const [isLoggedIn, setLoggedIn] = useState(false);
  useEffect(() => {
    const token = localStorage.getItem("authToken");
    if (token != null) {
      setLoggedIn(true);
    }
  }, []);

  useEffect(() => {
    const userId = localStorage.getItem("userId");
    if (!userId) {
      window.location.href = "/";
      return;
    }
    if (userId) {
      setNewTransaction((prevState) => ({
        ...prevState,
        userId: parseInt(userId, 10),
      }));
      fetchTotalIncome(parseInt(userId, 10));
    }
  }, []);

  useEffect(() => {
    const fetchTransactions = async () => {
      try {
        const response = await axios.get(
          `http://localhost:8000/api/expense/get/${newTransaction.userId}`
        );
        setTransactions(response.data);
      } catch (error) {
        console.error("Error fetching transactions:", error);
      }
    };

    if (newTransaction.userId) {
      fetchTransactions();
    }
  }, [newTransaction.userId]);

  useEffect(() => {
    const calculateExpenses = () => {
      const total = transactions.reduce(
        (sum, transaction) => sum + Number(transaction.amount),
        0
      );
      setTotalExpenses(total);

      const categorySums = transactions.reduce((acc, transaction) => {
        if (!acc[transaction.category]) {
          acc[transaction.category] = 0;
        }
        acc[transaction.category] += Number(transaction.amount);
        return acc;
      }, {});

      const categories = Object.keys(categorySums).map((category) => ({
        category,
        value: categorySums[category],
        percentage:
          total === 0 ? 0 : ((categorySums[category] / total) * 100).toFixed(2),
      }));

      setCategoryExpenses(categories);
    };

    calculateExpenses();
  }, [transactions]);

  const fetchTotalIncome = async (userId) => {
    try {
      const response = await axios.get(
        `http://localhost:8000/api/expense/get/income/${userId}`
      );
      const incomeData = response.data[0];
      setTotalIncome(parseFloat(incomeData.totalincome));
    } catch (error) {
      console.error("Error fetching total income:", error);
    }
  };

  const handleSaveTransaction = async (e) => {
    e.preventDefault();
    try {
      const response = await axios.post(
        "http://localhost:8000/api/expense/save",
        newTransaction
      );
      setTransactions([...transactions, response.data]);
      setNewTransaction({
        date: "",
        category: "",
        amount: "",
        description: "",
        userId: newTransaction.userId,
      });
      document.getElementById("transactionDrawer").click();
    } catch (error) {
      console.error("Error saving transaction:", error);
    }
  };

  const handleUpdateTransaction = async (e) => {
    e.preventDefault();
    try {
      const response = await axios.put(
        `http://localhost:8000/api/expense/put/${editingTransaction.id}`,
        editingTransaction
      );
      setTransactions(
        transactions.map((transaction) =>
          transaction.id === editingTransaction.id ? response.data : transaction
        )
      );
      setEditingTransaction(null);
      document.getElementById("updateTransactionDrawer").click();
    } catch (error) {
      console.error("Error updating transaction:", error);
    }
  };

  const handleEditTransaction = (transaction) => {
    setEditingTransaction(transaction);
    document.getElementById("updateTransactionDrawer").click();
  };

  const handleDeleteTransaction = async (id) => {
    try {
      await axios.delete(`http://localhost:8000/api/expense/delete/${id}`);
      setTransactions(
        transactions.filter((transaction) => transaction.id !== id)
      );
    } catch (error) {
      console.error("Error deleting transaction:", error);
    }
  };

  const handleEditClick = () => {
    setEditing(true);
    setNewIncome(totalIncome);
  };

  const handleSaveClick = () => {
    const userId = newTransaction.userId;
    console.log(userId);
    if (!userId) {
      console.error("User ID not found");
      return;
    }
    const requestBody = {
      newIncome: newIncome,
    };
    axios
      .put(
        `http://localhost:8000/api/expense/put/updateincome/${userId}`,
        requestBody
      )
      .then((response) => {
        setEditing(false);
        setTotalIncome(newIncome);
      })
      .catch((error) => {
        console.error("Error updating income:", error);
      });
  };

  const handleCancelClick = () => {
    setEditing(false);
    setNewIncome(totalIncome);
  };

  useEffect(() => {
    setBalance(totalIncome - totalExpenses);
  }, [totalExpenses, totalIncome]);

  return (
    <div className="flex flex-col w-full">
      {isLoggedIn ? (
        <>
          <header className="flex h-14 lg:h-[60px] items-center gap-4 border-b bg-gray-100/40 px-6 dark:bg-gray-800/40">
            <Link className="lg:hidden" href="#">
              <span className="sr-only">Home</span>
            </Link>
            <div className="flex-1">
              <h1 className="font-semibold text-lg md:text-xl">
                Personal Finance
              </h1>
            </div>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  className="rounded-full border border-gray-200 w-8 h-8 dark:border-gray-800"
                  size="icon"
                  variant="ghost"
                >
                  <img
                    alt="Avatar"
                    className="rounded-full"
                    height="32"
                    src="https://st3.depositphotos.com/6672868/13701/v/450/depositphotos_137014128-stock-illustration-user-profile-icon.jpg"
                    style={{
                      aspectRatio: "32/32",
                      objectFit: "cover",
                    }}
                    width="32"
                  />
                  <span className="sr-only">Toggle user menu</span>
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <Link href="/" passHref>
                  <DropdownMenuItem asChild>
                    <a>Logout</a>
                  </DropdownMenuItem>
                </Link>
              </DropdownMenuContent>
            </DropdownMenu>
          </header>
          <main className="flex flex-1 flex-col gap-4 p-4 md:gap-8 md:p-6">
            <div className="grid gap-6">
              <div className="grid md:grid-cols-3 gap-6">
                <Card>
                  <div className="relative">
                    {editing ? (
                      <>
                        <input
                          type="number"
                          value={newIncome}
                          onChange={(e) =>
                            setNewIncome(parseFloat(e.target.value))
                          }
                          className="w-full p-1 border border-gray-300 rounded"
                        />
                        <div className="absolute top-0 right-0 m-2 space-x-2">
                          <Button
                            variant="outline"
                            size="normal"
                            onClick={handleSaveClick}
                          >
                            Save
                          </Button>
                          <Button
                            size="normal"
                            variant="outline"
                            onClick={handleCancelClick}
                          >
                            Cancel
                          </Button>
                        </div>
                      </>
                    ) : (
                      <>
                        <CardHeader>
                          <CardDescription>Total Income</CardDescription>
                          <CardTitle>
                            ₹{totalIncome ? totalIncome.toFixed(2) : "0.00"}
                          </CardTitle>
                        </CardHeader>
                        <div className="absolute top-0 right-0 m-2">
                          <Button
                            size="normal"
                            variant="ghost"
                            onClick={handleEditClick}
                          >
                            Edit
                          </Button>
                        </div>
                      </>
                    )}
                    {!editing && totalIncome === 0 && (
                      <div className="absolute bottom-0 left-0 m-2 w-[150px] h-[40px] bg-red-100 text-red-800 border border-red-200 rounded flex items-center justify-center text-xs">
                        <p>Total income is 0. Please update your income.</p>
                      </div>
                    )}
                  </div>
                </Card>
                <Card>
                  <CardHeader>
                    <CardDescription>Total Expenses</CardDescription>
                    <CardTitle>₹{totalExpenses.toFixed(2)}</CardTitle>
                  </CardHeader>
                </Card>
                <Card>
                  <CardHeader>
                    <CardDescription>Balance</CardDescription>
                    <CardTitle>₹{balance.toFixed(2)}</CardTitle>
                  </CardHeader>
                </Card>
              </div>
              <div className="grid gap-6">
                <div className="flex items-center gap-4">
                  <Drawer>
                    <DrawerTrigger asChild>
                      <Button size="icon" variant="outline">
                        <PlusIcon className="h-4 w-4" />
                        <span className="sr-only">Add Transaction</span>
                      </Button>
                    </DrawerTrigger>
                    <DrawerContent>
                      <DrawerHeader>
                        <DrawerTitle>Add Transaction</DrawerTitle>
                        <DrawerDescription>
                          Enter the details of your new transaction.
                        </DrawerDescription>
                      </DrawerHeader>
                      <div className="px-4">
                        <form
                          className="space-y-4"
                          onSubmit={handleSaveTransaction}
                        >
                          <div className="grid gap-2">
                            <Label htmlFor="date">Date</Label>
                            <Input
                              id="date"
                              type="date"
                              value={newTransaction.date}
                              onChange={(e) =>
                                setNewTransaction({
                                  ...newTransaction,
                                  date: e.target.value,
                                })
                              }
                            />
                          </div>
                          <div className="grid gap-2">
                            <Label htmlFor="category">Category</Label>
                            <Select
                              id="category"
                              value={newTransaction.category}
                              onValueChange={(value) =>
                                setNewTransaction({
                                  ...newTransaction,
                                  category: value,
                                })
                              }
                            >
                              <SelectTrigger>
                                <SelectValue placeholder="Select category" />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="rent">Rent</SelectItem>
                                <SelectItem value="groceries">
                                  Groceries
                                </SelectItem>
                                <SelectItem value="utilities">
                                  Utilities
                                </SelectItem>
                                <SelectItem value="entertainment">
                                  Entertainment
                                </SelectItem>
                              </SelectContent>
                            </Select>
                          </div>
                          <div className="grid gap-2">
                            <Label htmlFor="amount">Amount</Label>
                            <Input
                              id="amount"
                              type="number"
                              value={newTransaction.amount}
                              onChange={(e) =>
                                setNewTransaction({
                                  ...newTransaction,
                                  amount: e.target.value,
                                })
                              }
                            />
                          </div>
                          <div className="grid gap-2">
                            <Label htmlFor="description">Description</Label>
                            <Textarea
                              id="description"
                              value={newTransaction.description}
                              onChange={(e) =>
                                setNewTransaction({
                                  ...newTransaction,
                                  description: e.target.value,
                                })
                              }
                            />
                          </div>
                          <DrawerFooter>
                            <Button type="submit">Save Transaction</Button>
                            <DrawerClose asChild id="transactionDrawer">
                              <Button variant="outline">Cancel</Button>
                            </DrawerClose>
                          </DrawerFooter>
                        </form>
                      </div>
                    </DrawerContent>
                  </Drawer>
                  <h2 className="font-semibold text-lg md:text-xl">
                    Transactions
                  </h2>
                </div>
                <div className="border shadow-sm rounded-lg">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Date</TableHead>
                        <TableHead>Category</TableHead>
                        <TableHead>Amount</TableHead>
                        <TableHead>Description</TableHead>
                        <TableHead className="text-right">Actions</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {transactions.map((transaction) => (
                        <TableRow key={transaction.id}>
                          <TableCell>
                            {new Date(transaction.date).toLocaleDateString()}
                          </TableCell>
                          <TableCell>{transaction.category}</TableCell>
                          <TableCell>
                            ₹{Number(transaction.amount).toFixed(2)}
                          </TableCell>
                          <TableCell>{transaction.description}</TableCell>
                          <TableCell className="text-right">
                            <Button
                              size="icon"
                              variant="ghost"
                              onClick={() =>
                                handleDeleteTransaction(transaction.id)
                              }
                            >
                              <TrashIcon className="h-4 w-4" />
                            </Button>
                            <Button
                              size="icon"
                              variant="ghost"
                              onClick={() => handleEditTransaction(transaction)}
                            >
                              <DeleteIcon className="h-4 w-4" />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </div>

              <div className="grid md:grid-cols-2 gap-6">
                <div className="border shadow-sm rounded-lg">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Category</TableHead>
                        <TableHead>Amount</TableHead>
                        <TableHead>Percentage</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {categoryExpenses.map((expense, index) => (
                        <TableRow key={index}>
                          <TableCell>{expense.category}</TableCell>
                          <TableCell>₹{expense.value.toFixed(2)}</TableCell>
                          <TableCell>{expense.percentage}%</TableCell>
                        </TableRow>
                      ))}
                      <TableRow>
                        <TableCell>Total</TableCell>
                        <TableCell>₹{totalExpenses.toFixed(2)}</TableCell>
                        <TableCell>100%</TableCell>
                      </TableRow>
                    </TableBody>
                  </Table>
                </div>

                <Card>
                  <div className="flex justify-center">
                    <CardHeader>
                      <CardTitle>Expense Breakdown</CardTitle>
                    </CardHeader>
                  </div>

                  <div className="flex justify-center">
                    <CardContent>
                      <BarChart
                        data={categoryExpenses.map(({ category, value }) => ({
                          category,
                          value,
                        }))}
                        className="aspect-square w-[450px]"
                      />
                    </CardContent>
                  </div>
                </Card>
              </div>
            </div>
          </main>
          <Drawer>
            <DrawerTrigger asChild>
              <Button
                id="updateTransactionDrawer"
                style={{ display: "none" }}
              />
            </DrawerTrigger>
            <DrawerContent>
              <DrawerHeader>
                <DrawerTitle>Update Transaction</DrawerTitle>
                <DrawerDescription>
                  Edit the details of your transaction.
                </DrawerDescription>
              </DrawerHeader>
              <div className="px-4">
                <form className="space-y-4" onSubmit={handleUpdateTransaction}>
                  <div className="grid gap-2">
                    <Label htmlFor="date">Date</Label>
                    <Input
                      id="date"
                      type="date"
                      value={editingTransaction?.date || ""}
                      onChange={(e) =>
                        setEditingTransaction({
                          ...editingTransaction,
                          date: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="category">Category</Label>
                    <Select
                      id="category"
                      value={editingTransaction?.category || ""}
                      onValueChange={(value) =>
                        setEditingTransaction({
                          ...editingTransaction,
                          category: value,
                        })
                      }
                    >
                      <SelectTrigger>
                        <SelectValue placeholder="Select category" />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="rent">Rent</SelectItem>
                        <SelectItem value="groceries">Groceries</SelectItem>
                        <SelectItem value="utilities">Utilities</SelectItem>
                        <SelectItem value="entertainment">
                          Entertainment
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="amount">Amount</Label>
                    <Input
                      id="amount"
                      type="number"
                      value={editingTransaction?.amount || ""}
                      onChange={(e) =>
                        setEditingTransaction({
                          ...editingTransaction,
                          amount: e.target.value,
                        })
                      }
                    />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="description">Description</Label>
                    <Textarea
                      id="description"
                      value={editingTransaction?.description || ""}
                      onChange={(e) =>
                        setEditingTransaction({
                          ...editingTransaction,
                          description: e.target.value,
                        })
                      }
                    />
                  </div>
                  <DrawerFooter>
                    <Button type="submit">Update Transaction</Button>
                    <DrawerClose asChild>
                      <Button variant="outline">Cancel</Button>
                    </DrawerClose>
                  </DrawerFooter>
                </form>
              </div>
            </DrawerContent>
          </Drawer>
        </>
      ) : (
        <p className="text-center mt-8">
          You are not authorized to access this page. Please sign in to view
          your finances.
        </p>
      )}
    </div>
  );
}

function BarChart({ data, ...props }) {
  return (
    <div {...props} style={{ height: 250 }}>
      <ResponsiveBar
        data={data}
        keys={["value"]}
        indexBy="category"
        margin={{ top: 10, right: 10, bottom: 50, left: 50 }}
        padding={0.3}
        colors={{ scheme: "accent" }}
        borderColor={{ from: "color", modifiers: [["darker", 1.6]] }}
        axisTop={null}
        axisRight={null}
        axisBottom={{
          tickSize: 5,
          tickPadding: 5,
          tickRotation: 0,
          legend: "Category",
          legendPosition: "middle",
          legendOffset: 32,
        }}
        axisLeft={{
          tickSize: 5,
          tickPadding: 5,
          tickRotation: 0,
          legend: "Value",
          legendPosition: "middle",
          legendOffset: -40,
        }}
        labelSkipWidth={12}
        labelSkipHeight={12}
        labelTextColor={{ from: "color", modifiers: [["darker", 1.6]] }}
        animate={true}
        motionStiffness={90}
        motionDamping={15}
        theme={{
          labels: {
            text: {
              fontSize: "18px",
            },
          },
          tooltip: {
            container: {
              fontSize: "12px",
              textTransform: "capitalize",
              borderRadius: "6px",
            },
          },
        }}
        role="application"
      />
    </div>
  );
}
function DeleteIcon(props) {
  return (
    <svg
      {...props}
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M20 5H9l-7 7 7 7h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2Z" />
      <line x1="18" x2="12" y1="9" y2="15" />
      <line x1="12" x2="18" y1="9" y2="15" />
    </svg>
  );
}

function PlusIcon() {
  return (
    <div className="flex">
      <span>Add</span>
    </div>
  );
}

function TrashIcon(props) {
  return (
    <svg
      {...props}
      xmlns="http://www.w3.org/2000/svg"
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M3 6h18" />
      <path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6" />
      <path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2" />
    </svg>
  );
}

"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";

export default function Stocks() {
  const [items, setItems] = useState([]);
  const [newItem, setNewItem] = useState("");
  const addItem = () => {
    if (newItem.trim() !== "") {
      setItems([...items, newItem.trim()]);
      setNewItem("");
    }
  };
  return (
    <div className="flex flex-col items-center h-screen">
      <div className="flex items-center">Hello from the stocks page</div>
      <div className="w-full max-w-md mx-auto p-4 space-y-4">
        <div className="flex items-center space-x-2">
          <Input
            type="text"
            placeholder="Enter a value"
            value={newItem}
            onChange={(e) => setNewItem(e.target.value)}
            className="flex-1"
          />
          <Button onClick={addItem}>Add</Button>
        </div>
        <ul className="space-y-2">
          {items.map((item, index) => (
            <li
              key={index}
              className="bg-gray-100 dark:bg-gray-800 rounded-md px-4 py-2 flex items-center justify-between"
            >
              <span>{item}</span>
              <Button
                variant="ghost"
                size="icon"
                onClick={() => setItems(items.filter((_, i) => i !== index))}
              >
                <XIcon className="w-4 h-4" />
              </Button>
            </li>
          ))}
        </ul>
        <div className="bg-gray-100 dark:bg-gray-800 rounded-md p-4">
          <h2 className="text-lg font-medium mb-2">Entered Values</h2>
          <ul className="space-y-2">
            {items.map((item, index) => (
              <li key={index} className="flex items-center justify-between">
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}

function XIcon(props) {
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
      <path d="M18 6 6 18" />
      <path d="m6 6 12 12" />
    </svg>
  );
}
